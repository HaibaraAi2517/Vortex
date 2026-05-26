package com.vortex.kernel.hmc;

import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.exception.EmbeddingException;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.l1.CaffeineHotStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link RecallOrchestrator}.
 *
 * Verifies the semantic recall pipeline: L1 scoring, L2 fallback,
 * L3 enrichment, tag filtering, token budget enforcement,
 * and deduplication.
 */
class RecallOrchestratorTest {

    private static final MemorySloTracker SLO_TRACKER = new MemorySloTracker(new SimpleMeterRegistry());
    private static final AdaptiveWeightLearner WEIGHT_LEARNER =
            new AdaptiveWeightLearner(new ShadowEvaluationTracker(0.20, 14), 0.05, 100, 0.3, 0.5, 0.2);

    private CaffeineHotStore l1;
    private FakeL2WarmStore l2;
    private FakeL3ColdStore l3;
    private EmbeddingService embedding;
    private SemanticEvictionPolicy evictionPolicy;
    private EvictionRegretTracker regretTracker;
    private FragmentPersistenceManager persistenceManager;
    private FragmentPinManager pinManager;
    private TieredEvictionCoordinator evictionCoordinator;
    private RedundancyAnalyzer redundancyAnalyzer;
    private RecallOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        l1 = new CaffeineHotStore(512);
        l2 = new FakeL2WarmStore(4);
        l3 = new FakeL3ColdStore();
        embedding = new FixedEmbeddingService(4);

        evictionPolicy = new SemanticEvictionPolicy(0.3, 0.5, 0.2);
        NamespaceQuotaManager quotaManager = new NamespaceQuotaManager(0.25, 0.15, 16);
        EvictionDecisionLogger decisionLogger = new EvictionDecisionLogger(SLO_TRACKER);
        regretTracker = new EvictionRegretTracker(3_600_000L, System::currentTimeMillis);
        persistenceManager = createPersistenceManager();

        ObjectProvider<EmbeddingService> cloudProvider = emptyProvider();
        pinManager = new FragmentPinManager(l1, l2, l3, persistenceManager, embedding, cloudProvider, null);

        evictionCoordinator = new TieredEvictionCoordinator(
                l1, evictionPolicy, quotaManager, decisionLogger, regretTracker,
                SLO_TRACKER, persistenceManager, WEIGHT_LEARNER, pinManager,
                0.85, 300_000, 64, 2);
        pinManager.setEvictionCoordinator(evictionCoordinator);

        redundancyAnalyzer = new RedundancyAnalyzer();

        orchestrator = new RecallOrchestrator(
                l1, l2, l3, embedding, cloudProvider,
                WEIGHT_LEARNER, evictionPolicy, regretTracker, SLO_TRACKER,
                persistenceManager, emptyPagingProvider(),
                redundancyAnalyzer, pinManager, evictionCoordinator);
    }

    // ========================================================================
    // cosineSimilarity
    // ========================================================================

    @Test
    void cosineSimilaritySameVectorsReturnsOne() {
        float[] v = new float[]{1.0f, 2.0f, 3.0f};
        double result = orchestrator.cosineSimilarity(v, v);
        assertThat(result).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void cosineSimilarityOrthogonalVectorsReturnsZero() {
        float[] a = new float[]{1.0f, 0.0f};
        float[] b = new float[]{0.0f, 1.0f};
        double result = orchestrator.cosineSimilarity(a, b);
        assertThat(result).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void cosineSimilarityNullFirstReturnsZero() {
        double result = orchestrator.cosineSimilarity(null, new float[]{1.0f, 0.0f});
        assertThat(result).isEqualTo(0.0);
    }

    @Test
    void cosineSimilarityNullSecondReturnsZero() {
        double result = orchestrator.cosineSimilarity(new float[]{1.0f, 0.0f}, null);
        assertThat(result).isEqualTo(0.0);
    }

    @Test
    void cosineSimilarityBothNullReturnsZero() {
        double result = orchestrator.cosineSimilarity(null, null);
        assertThat(result).isEqualTo(0.0);
    }

    // ========================================================================
    // recall: L1 results
    // ========================================================================

    @Test
    void recallReturnsL1ResultsFirst() {
        MemoryFragment f1 = fragment("l1-a", "ns", "L1 fragment A", List.of(), 4);
        f1.setImportance(0.8);
        MemoryFragment f2 = fragment("l1-b", "ns", "L1 fragment B", List.of(), 4);
        f2.setImportance(0.6);

        l1.put(f1);
        l1.put(f2);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("test query")
                .namespace("ns")
                .topK(2)
                .tokenBudget(200)
                .build());

        assertThat(result.getFragments()).hasSize(2);
        assertThat(result.getFragments()).extracting(f -> f.getFragment().getId())
                .contains("l1-a", "l1-b");
    }

    // ========================================================================
    // recall: L2 fallback
    // ========================================================================

    @Test
    void recallFallsBackToL2WhenL1Insufficient() {
        MemoryFragment f1 = fragment("l1-only", "ns", "only one in L1", List.of(), 4);
        f1.setImportance(0.5);
        l1.put(f1);

        MemoryFragment l2Hit = fragment("l2-hit", "ns", "from L2", List.of(), 4);
        l2.seedSearchResults(List.of(l2Hit));

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("test query")
                .namespace("ns")
                .topK(3)
                .tokenBudget(300)
                .build());

        assertThat(result.getFragments()).isNotEmpty();
        assertThat(result.getFragments().size()).isGreaterThanOrEqualTo(2);
    }

    // ========================================================================
    // recall: L3 enrichment
    // ========================================================================

    @Test
    void recallEnrichesL2HitsFromL3() {
        MemoryFragment l2Shell = fragment("l2-rich", "ns", "shell content", List.of(), 4);
        l2.seedSearchResults(List.of(l2Shell));

        MemoryFragment archived = fragment("l2-rich", "ns", "full content", List.of("role:user"), 4);
        archived.setReasoningChainId("chain-1");
        archived.pinForMillis(60_000L);
        l3.archiveFragment(archived);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .tags(List.of("role:user"))
                .build());

        assertThat(result.getFragments()).hasSize(1);
        MemoryFragment recalled = result.getFragments().getFirst().getFragment();
        assertThat(recalled.getTags()).contains("role:user");
        assertThat(recalled.getReasoningChainId()).isEqualTo("chain-1");
        assertThat(recalled.isPinned()).isTrue();
    }

    // ========================================================================
    // recall: topK and tokenBudget
    // ========================================================================

    @Test
    void recallRespectsTopK() {
        MemoryFragment f1 = fragment("k1", "ns", "a", List.of(), 4);
        MemoryFragment f2 = fragment("k2", "ns", "b", List.of(), 4);
        MemoryFragment f3 = fragment("k3", "ns", "c", List.of(), 4);

        l1.put(f1);
        l1.put(f2);
        l1.put(f3);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(2)
                .tokenBudget(200)
                .build());

        assertThat(result.getFragments()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void recallRespectsTokenBudget() {
        MemoryFragment f1 = fragment("tb1", "ns", "x".repeat(100), List.of(), 4);
        f1.setTokenCount(100);
        MemoryFragment f2 = fragment("tb2", "ns", "y".repeat(100), List.of(), 4);
        f2.setTokenCount(100);

        l1.put(f1);
        l1.put(f2);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(5)
                .tokenBudget(50)
                .build());

        long totalTokens = result.getFragments().stream()
                .mapToLong(f -> f.getFragment().getTokenCount())
                .sum();
        assertThat(totalTokens).isLessThanOrEqualTo(50);
    }

    // ========================================================================
    // recall: deduplication
    // ========================================================================

    @Test
    void recallDeduplicatesL2HitsAlreadyInL1() {
        MemoryFragment f1 = fragment("dup-1", "ns", "in L1", List.of(), 4);
        l1.put(f1);

        MemoryFragment l2Dup = fragment("dup-1", "ns", "also in L2", List.of(), 4);
        l2.seedSearchResults(List.of(l2Dup));

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(3)
                .tokenBudget(300)
                .build());

        long count = result.getFragments().stream()
                .filter(f -> "dup-1".equals(f.getFragment().getId()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    // ========================================================================
    // recall: tag filtering
    // ========================================================================

    @Test
    void recallFiltersByTagsCorrectly() {
        MemoryFragment tagged = fragment("tagged", "ns", "tagged", List.of("role:admin"), 4);
        MemoryFragment untagged = fragment("untagged", "ns", "untagged", List.of(), 4);

        l1.put(tagged);
        l1.put(untagged);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(2)
                .tokenBudget(200)
                .tags(List.of("role:admin"))
                .build());

        assertThat(result.getFragments()).extracting(f -> f.getFragment().getId())
                .contains("tagged")
                .doesNotContain("untagged");
    }

    @Test
    void recallHandlesEmptyTagsGracefully() {
        MemoryFragment f1 = fragment("no-tag-1", "ns", "content", List.of(), 4);
        l1.put(f1);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .tags(List.of())
                .build());

        assertThat(result.getFragments()).isNotEmpty();
    }

    @Test
    void recallHandlesNullTagsGracefully() {
        MemoryFragment f1 = fragment("null-tag", "ns", "content", List.of(), 4);
        l1.put(f1);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .build());

        assertThat(result.getFragments()).isNotEmpty();
        assertThat(result.getFragments().getFirst().getFragment().getId()).isEqualTo("null-tag");
    }

    // ========================================================================
    // recall: scenario handling
    // ========================================================================

    @Test
    void recallHandlesNullScenarioGracefully() {
        MemoryFragment f1 = fragment("no-scenario", "ns", "content", List.of(), 4);
        l1.put(f1);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .scenario(null)
                .build());

        assertThat(result.getFragments()).isNotEmpty();
    }

    @Test
    void recallWithCodingScenarioReturnsValidResult() {
        MemoryFragment f1 = fragment("coding-1", "ns", "def function():", List.of(), 4);
        l1.put(f1);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .scenario(MemoryScenario.CODING)
                .topK(1)
                .tokenBudget(100)
                .build());

        assertThat(result.getFragments()).isNotEmpty();
        assertThat(result.getRecallSessionId()).isNotBlank();
    }

    // ========================================================================
    // recall: edge cases
    // ========================================================================

    @Test
    void recallWithEmptyL1AndNoL2HitsReturnsEmpty() {
        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(3)
                .tokenBudget(300)
                .build());

        assertThat(result.getFragments()).isEmpty();
    }

    @Test
    void recallProducesValidRecallSessionId() {
        MemoryFragment f1 = fragment("session", "ns", "content", List.of(), 4);
        l1.put(f1);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .build());

        assertThat(result.getRecallSessionId()).isNotNull();
        assertThat(result.getRecallSessionId()).isNotBlank();
    }

    @Test
    void recallRecordsSourceTrace() {
        MemoryFragment f1 = fragment("trace-1", "ns", "content", List.of(), 4);
        l1.put(f1);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .build());

        assertThat(result.getFragments()).isNotEmpty();
        assertThat(result.getSourceTrace()).isNotNull();
    }

    // ========================================================================
    // recall: profile names
    // ========================================================================

    @Test
    void recallProducesProfileNamesInResult() {
        MemoryFragment f1 = fragment("profile", "ns", "profile test", List.of(), 4);
        l1.put(f1);

        RecallResult result = orchestrator.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .build());

        assertThat(result.getFragments()).isNotEmpty();
        assertThat(result.getActiveProfileName()).isNotNull();
    }

    // ========================================================================
    // recall: EmbeddingException propagation
    // ========================================================================

    @Test
    void recallWithFailingEmbeddingServicePropagatesException() {
        FailingEmbeddingService failingEmbedding = new FailingEmbeddingService(4);
        ObjectProvider<EmbeddingService> cloudProvider = emptyProvider();
        FragmentPinManager fpm = new FragmentPinManager(l1, l2, l3, persistenceManager, failingEmbedding, cloudProvider, null);

        RecallOrchestrator failingOrch = new RecallOrchestrator(
                l1, l2, l3, failingEmbedding, cloudProvider,
                WEIGHT_LEARNER, evictionPolicy, regretTracker, SLO_TRACKER,
                persistenceManager, emptyPagingProvider(),
                redundancyAnalyzer, fpm, evictionCoordinator);

        MemoryFragment f1 = fragment("fail-emb", "ns", "content", List.of(), 4);
        l1.put(f1);

        assertThatThrownBy(() -> failingOrch.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .build()))
                .isInstanceOf(EmbeddingException.class);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static MemoryFragment fragment(String id, String namespace, String content, List<String> tags, int dim) {
        return MemoryFragment.builder()
                .id(id)
                .namespace(namespace)
                .content(content)
                .embedding(vector(dim))
                .tokenCount(Math.max(1, content.length()))
                .importance(0.5)
                .lastAccessTime(System.currentTimeMillis())
                .createdAt(Instant.now())
                .tags(tags)
                .build();
    }

    private static float[] vector(int dim) {
        float[] v = new float[dim];
        v[0] = 1.0f;
        return v;
    }

    private static ObjectProvider<EmbeddingService> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public EmbeddingService getObject(Object... args) { return null; }
            @Override
            public EmbeddingService getIfAvailable() { return null; }
            @Override
            public EmbeddingService getIfUnique() { return null; }
            @Override
            public EmbeddingService getObject() { return null; }
            @Override
            public Iterator<EmbeddingService> iterator() { return Collections.emptyIterator(); }
        };
    }

    private static ObjectProvider<com.vortex.kernel.paging.SemanticPagingManager> emptyPagingProvider() {
        return new ObjectProvider<>() {
            @Override
            public com.vortex.kernel.paging.SemanticPagingManager getObject(Object... args) { return null; }
            @Override
            public com.vortex.kernel.paging.SemanticPagingManager getIfAvailable() { return null; }
            @Override
            public com.vortex.kernel.paging.SemanticPagingManager getIfUnique() { return null; }
            @Override
            public com.vortex.kernel.paging.SemanticPagingManager getObject() { return null; }
            @Override
            public Iterator<com.vortex.kernel.paging.SemanticPagingManager> iterator() { return Collections.emptyIterator(); }
        };
    }

    private FragmentPersistenceManager createPersistenceManager() {
        try {
            Path queueFile = Files.createTempFile("vortex-ro-test-dlq", ".jsonl");
            Path processedFile = Files.createTempFile("vortex-ro-test-processed", ".txt");
            FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                    queueFile,
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
            FileBackedProcessedTaskStore processedTaskStore = new FileBackedProcessedTaskStore(processedFile);
            return new FragmentPersistenceManager(
                    l2, l3, queue, processedTaskStore, new MemorySloTracker(new SimpleMeterRegistry()), false,
                    Runnable::run);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static class FixedEmbeddingService implements EmbeddingService {
        private final int dimension;

        private FixedEmbeddingService(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public float[] embed(String text) { return vector(dimension); }

        @Override
        public int dimension() { return dimension; }
    }

    private static class FailingEmbeddingService implements EmbeddingService {
        private final int dimension;

        private FailingEmbeddingService(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public float[] embed(String text) {
            throw new EmbeddingException("simulated embedding failure");
        }

        @Override
        public int dimension() { return dimension; }
    }

    private static class FakeL2WarmStore implements L2WarmStore {
        private final int dimension;
        private final List<MemoryFragment> searchResults = new ArrayList<>();

        private FakeL2WarmStore(int dimension) {
            this.dimension = dimension;
        }

        void seedSearchResults(List<MemoryFragment> fragments) {
            searchResults.clear();
            searchResults.addAll(fragments);
        }

        @Override
        public void upsert(MemoryFragment fragment) {}

        @Override
        public List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK) {
            return searchResults.stream()
                    .filter(f -> namespace.equals(f.getNamespace()))
                    .limit(topK)
                    .toList();
        }

        @Override
        public Optional<MemoryFragment> get(String id) {
            return searchResults.stream().filter(f -> f.getId().equals(id)).findFirst();
        }

        @Override
        public void delete(String id) {}

        @Override
        public int vectorDimension() { return dimension; }
    }

    private static class FakeL3ColdStore implements L3ColdStore {
        private final Map<String, MemoryFragment> fragments = new HashMap<>();

        @Override
        public void archiveFragment(MemoryFragment fragment) {
            fragments.put(fragment.getId(), fragment);
        }

        @Override
        public Optional<MemoryFragment> retrieveFragment(String id) {
            return Optional.ofNullable(fragments.get(id));
        }

        @Override
        public String saveCheckpoint(com.vortex.common.model.TaskState state) { return null; }

        @Override
        public com.vortex.common.model.CheckpointMetadata saveCheckpointWithMetadata(
                com.vortex.common.model.TaskState state, com.vortex.common.model.CheckpointMetadata meta) { return null; }

        @Override
        public com.vortex.common.model.CheckpointMetadata saveCheckpointBytesWithMetadata(
                byte[] data, com.vortex.common.model.CheckpointMetadata meta) { return null; }

        @Override
        public Optional<com.vortex.common.model.TaskState> loadCheckpoint(String checkpointId) { return Optional.empty(); }

        @Override
        public void deleteCheckpoint(String checkpointId) {}

        @Override
        public void putBytes(String key, byte[] data) {}

        @Override
        public byte[] getBytes(String key) { return null; }

        @Override
        public List<com.vortex.common.model.CheckpointMetadata> listCheckpointMetadata(String taskId) { return List.of(); }

        @Override
        public Set<String> listTaskIdsWithCheckpoints() { return Set.of(); }
    }
}
