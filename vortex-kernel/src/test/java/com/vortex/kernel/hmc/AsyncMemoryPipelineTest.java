package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.dto.RerankerType;
import com.vortex.common.dto.RetrievalMode;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.paging.SemanticPagingManager;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.l1.CaffeineHotStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncMemoryPipelineTest {

    @Test
    void submitShouldMakeWriteRecallableBeforeBackgroundProcessingCompletes() throws Exception {
        String content = "User preference: deploy service-orchid only after the canary check passes.";
        CaffeineHotStore l1 = new CaffeineHotStore(1024);
        TestL2WarmStore l2 = new TestL2WarmStore(4);
        TestL3ColdStore l3 = new TestL3ColdStore();
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);
        HierarchicalMemoryController hmc = createHmc(l1, l2, l3, new FixedEmbeddingService(4));
        AsyncMemoryPipeline pipeline = new AsyncMemoryPipeline(
                hmc,
                new BlockingExtractionService(extractionStarted, releaseExtraction, content),
                new MemorySummaryService(text -> text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length, 1200),
                1,
                128,
                8);

        try {
            MemoryPipelineStatus accepted = pipeline.submit(MemoryPipelineRequest.builder()
                    .pipelineId("write-through-pipeline")
                    .namespace("write-through-ns")
                    .content(content)
                    .tags(List.of("write-through"))
                    .build());

            assertThat(extractionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(accepted.getCompletedStages()).contains(MemoryPipelineStage.L1_WRITE_THROUGH);
            assertThat(accepted.getFragmentIds()).isNotEmpty();
            assertThat(accepted.getFragmentIds())
                    .allMatch(id -> l1.peek(id).orElseThrow().isPinned());
            assertThat(accepted.getFragmentIds())
                    .allMatch(id -> Long.valueOf(Long.MAX_VALUE)
                            .equals(l1.peek(id).orElseThrow().getPinnedUntil()));

            RecallResult recalled = hmc.recall(RecallQuery.builder()
                    .namespace("write-through-ns")
                    .query("What deployment guardrail applies to service-orchid?")
                    .tags(List.of("write-through"))
                    .retrievalMode(RetrievalMode.HYBRID)
                    .rerankEnabled(true)
                    .rerankerType(RerankerType.LINEAR_SCORE_FUSION)
                    .topK(3)
                    .tokenBudget(256)
                    .build());

            assertThat(recalled.getFragments())
                    .extracting(result -> result.getFragment().getContent())
                    .anyMatch(recalledContent -> recalledContent.contains("service-orchid")
                            && recalledContent.contains("canary check"));

            List<String> transientIds = List.copyOf(accepted.getFragmentIds());
            releaseExtraction.countDown();
            MemoryPipelineStatus completed = waitForCompletion(pipeline, accepted.getPipelineId());
            assertThat(completed.getStatus()).isEqualTo(MemoryPipelineStatusCode.COMPLETED);
            assertThat(transientIds).allMatch(id -> l1.peek(id).isEmpty());
        } finally {
            releaseExtraction.countDown();
            pipeline.shutdown();
        }
    }

    @Test
    void asyncFailureShouldRestoreOriginalPinStateAndRetainWriteThroughContent() {
        String content = "Remember that service-lilac uses the blue rollback queue.";
        CaffeineHotStore l1 = new CaffeineHotStore(1024);
        TestL2WarmStore l2 = new FailingL2WarmStore(4);
        TestL3ColdStore l3 = new TestL3ColdStore();
        AsyncMemoryPipeline pipeline = new AsyncMemoryPipeline(
                createHmc(l1, l2, l3, new FixedEmbeddingService(4)),
                new MemoryExtractionService(12),
                new MemorySummaryService(text -> text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length, 1200),
                1,
                128,
                8);

        try {
            MemoryPipelineStatus accepted = pipeline.submit(MemoryPipelineRequest.builder()
                    .pipelineId("write-through-failure")
                    .namespace("write-through-failure-ns")
                    .content(content)
                    .build());
            List<String> transientIds = List.copyOf(accepted.getFragmentIds());

            MemoryPipelineStatus failed = waitForCompletion(pipeline, accepted.getPipelineId());

            assertThat(failed.getStatus()).isEqualTo(MemoryPipelineStatusCode.FAILED);
            assertThat(transientIds).isNotEmpty();
            assertThat(transientIds).allMatch(id -> l1.peek(id).isPresent());
            assertThat(transientIds)
                    .allMatch(id -> l1.peek(id).orElseThrow().getPinnedUntil() == null);
            assertThat(transientIds)
                    .allMatch(id -> content.equals(l1.peek(id).orElseThrow().getContent()));
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    void submitShouldCompleteExtractionSummaryEmbeddingIndexAndArchive() {
        CaffeineHotStore l1 = new CaffeineHotStore(1024);
        TestL2WarmStore l2 = new TestL2WarmStore(4);
        TestL3ColdStore l3 = new TestL3ColdStore();
        EmbeddingService embedding = new FixedEmbeddingService(4);
        HierarchicalMemoryController hmc = createHmc(l1, l2, l3, embedding);
        AsyncMemoryPipeline pipeline = new AsyncMemoryPipeline(
                hmc,
                new MemoryExtractionService(12),
                new MemorySummaryService(text -> text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length, 1200),
                2,
                128,
                16);

        MemoryPipelineStatus accepted = pipeline.submit(MemoryPipelineRequest.builder()
                .pipelineId("pipeline-test-001")
                .namespace("ns")
                .content("""
                        User preference: keep incident reports concise.
                        Tool result: staging deploy succeeded after cache rebuild.
                        Follow-up: record the rollback owner as platform-oncall.
                        """)
                .tags(List.of("async-pipeline-test"))
                .reasoningChainId("chain-1")
                .build());

        assertThat(accepted.getStatus()).isIn(MemoryPipelineStatusCode.ACCEPTED, MemoryPipelineStatusCode.RUNNING,
                MemoryPipelineStatusCode.COMPLETED);

        MemoryPipelineStatus completed = waitForCompletion(pipeline, accepted.getPipelineId());

        assertThat(completed.getStatus()).isEqualTo(MemoryPipelineStatusCode.COMPLETED);
        assertThat(completed.getCompletedStages()).contains(
                MemoryPipelineStage.EXTRACTION,
                MemoryPipelineStage.SUMMARY,
                MemoryPipelineStage.SPLIT,
                MemoryPipelineStage.EMBEDDING,
                MemoryPipelineStage.L1_ADMISSION,
                MemoryPipelineStage.L2_INDEX,
                MemoryPipelineStage.L3_ARCHIVE);
        assertThat(completed.getExtractedUnitCount()).isGreaterThanOrEqualTo(3);
        assertThat(completed.getSummaryTokenCount()).isGreaterThan(0);
        assertThat(completed.getFragmentIds()).isNotEmpty();
        String fragmentId = completed.getFragmentIds().getFirst();
        assertThat(l1.peek(fragmentId)).isPresent();
        assertThat(l2.get(fragmentId)).isPresent();
        assertThat(l3.retrieveFragment(fragmentId)).isPresent();
        assertThat(l3.retrieveFragment(fragmentId).orElseThrow().getContent())
                .contains("User preference")
                .contains("Tool result")
                .contains("Follow-up");

        pipeline.shutdown();
    }

    @Test
    void statusRetentionShouldNotEvictRunningPipelineWhenLimitIsReached() throws Exception {
        CaffeineHotStore l1 = new CaffeineHotStore(1024);
        TestL2WarmStore l2 = new TestL2WarmStore(4);
        TestL3ColdStore l3 = new TestL3ColdStore();
        HierarchicalMemoryController hmc = createHmc(l1, l2, l3, new FixedEmbeddingService(4));
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);
        AsyncMemoryPipeline pipeline = new AsyncMemoryPipeline(
                hmc,
                new BlockingExtractionService(extractionStarted, releaseExtraction),
                new MemorySummaryService(text -> text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length, 1200),
                1,
                16,
                8);

        try {
            pipeline.submit(MemoryPipelineRequest.builder()
                    .pipelineId("running-pipeline")
                    .namespace("ns")
                    .content("running content")
                    .build());
            assertThat(extractionStarted.await(1, TimeUnit.SECONDS)).isTrue();

            for (int i = 1; i <= 17; i++) {
                pipeline.processBlocking(MemoryPipelineRequest.builder()
                        .pipelineId("completed-" + i)
                        .namespace("ns")
                        .content("completed " + i)
                        .build());
            }

            assertThat(pipeline.snapshot("running-pipeline")).isPresent();
            assertThat(pipeline.snapshot("completed-17")).isPresent();
            assertThat(pipeline.snapshot("completed-1")).isEmpty();
        } finally {
            releaseExtraction.countDown();
            waitForCompletion(pipeline, "running-pipeline");
            pipeline.shutdown();
        }
    }

    @Test
    void pipelineFailureShouldPublishFailedStatusWithCompletedStages() {
        CaffeineHotStore l1 = new CaffeineHotStore(1024);
        TestL2WarmStore l2 = new FailingL2WarmStore(4);
        TestL3ColdStore l3 = new TestL3ColdStore();
        AsyncMemoryPipeline pipeline = new AsyncMemoryPipeline(
                createHmc(l1, l2, l3, new FixedEmbeddingService(4)),
                new MemoryExtractionService(12),
                new MemorySummaryService(text -> text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length, 1200),
                1,
                128,
                8);

        MemoryPipelineStatus failed = pipeline.processBlocking(MemoryPipelineRequest.builder()
                .pipelineId("failed-pipeline")
                .namespace("ns")
                .content("content that will fail during L2 persistence")
                .build());

        assertThat(failed.getStatus()).isEqualTo(MemoryPipelineStatusCode.FAILED);
        assertThat(failed.getCompletedStages()).contains(
                MemoryPipelineStage.ADMISSION,
                MemoryPipelineStage.EXTRACTION,
                MemoryPipelineStage.SUMMARY);
        assertThat(failed.getCompletedStages()).doesNotContain(MemoryPipelineStage.L2_INDEX, MemoryPipelineStage.L3_ARCHIVE);
        assertThat(failed.getErrorMessage()).contains("IllegalStateException").contains("simulated L2 failure");
        assertThat(pipeline.snapshot("failed-pipeline")).contains(failed);

        pipeline.shutdown();
    }

    private static MemoryPipelineStatus waitForCompletion(AsyncMemoryPipeline pipeline, String pipelineId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            MemoryPipelineStatus status = pipeline.snapshot(pipelineId).orElseThrow();
            if (status.getStatus() == MemoryPipelineStatusCode.COMPLETED
                    || status.getStatus() == MemoryPipelineStatusCode.FAILED) {
                return status;
            }
            pause(5L);
        }
        throw new AssertionError("Timed out waiting for pipeline completion");
    }

    private static HierarchicalMemoryController createHmc(
            CaffeineHotStore l1,
            TestL2WarmStore l2,
            TestL3ColdStore l3,
            EmbeddingService embedding) {
        MemorySloTracker sloTracker = new MemorySloTracker(new SimpleMeterRegistry());
        AdaptiveWeightLearner weightLearner =
                new AdaptiveWeightLearner(new ShadowEvaluationTracker(0.20, 14), 0.05, 100, 0.3, 0.5, 0.2);
        ObjectProvider<EmbeddingService> cloudProvider = emptyProvider();
        ObjectProvider<SemanticPagingManager> pagingProvider = emptyPagingProvider();
        SemanticEvictionPolicy evictionPolicy = new SemanticEvictionPolicy(0.3, 0.5, 0.2);
        NamespaceQuotaManager quotaManager = new NamespaceQuotaManager(0.25, 0.15, 16);
        EvictionDecisionLogger decisionLogger = new EvictionDecisionLogger(sloTracker);
        EvictionRegretTracker regretTracker = new EvictionRegretTracker(3_600_000L, System::currentTimeMillis);
        FragmentPersistenceManager pm = persistenceManager(l2, l3, sloTracker);
        FragmentPinManager pinMgr = new FragmentPinManager(l1, l2, l3, pm, embedding, cloudProvider, null);
        TieredEvictionCoordinator tec = new TieredEvictionCoordinator(
                l1, evictionPolicy, quotaManager, decisionLogger, regretTracker,
                sloTracker, pm, weightLearner, pinMgr,
                0.85, 300_000, 64, 2);
        pinMgr.setEvictionCoordinator(tec);
        RedundancyAnalyzer redundancyAnalyzer = new RedundancyAnalyzer();
        RecallOrchestrator recallOrch = new RecallOrchestrator(
                l1, l2, l3, embedding, cloudProvider, weightLearner,
                evictionPolicy, regretTracker, sloTracker, pm, pagingProvider,
                redundancyAnalyzer, pinMgr, tec);
        MemoryDiagnosticsCollector diagCollector = new MemoryDiagnosticsCollector(
                sloTracker, regretTracker, pagingProvider, weightLearner);

        return new HierarchicalMemoryController(
                l1, l2, l3, evictionPolicy, quotaManager, weightLearner,
                decisionLogger, regretTracker, sloTracker, pm,
                new SemanticTextSplitter(text -> text == null || text.isBlank() ? 0 : Math.max(1, text.length()), 256),
                embedding, cloudProvider, pagingProvider, 0.85,
                tec, pinMgr, recallOrch, diagCollector);
    }

    private static FragmentPersistenceManager persistenceManager(
            L2WarmStore l2,
            L3ColdStore l3,
            MemorySloTracker sloTracker) {
        try {
            Path queueFile = Files.createTempFile("vortex-async-pipeline-test-dlq", ".jsonl");
            Path processedFile = Files.createTempFile("vortex-async-pipeline-test-processed", ".txt");
            FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                    queueFile,
                    new ObjectMapper().findAndRegisterModules());
            FileBackedProcessedTaskStore processedTaskStore = new FileBackedProcessedTaskStore(processedFile);
            return new FragmentPersistenceManager(l2, l3, queue, processedTaskStore, sloTracker, false, Runnable::run);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ObjectProvider<SemanticPagingManager> emptyPagingProvider() {
        return new ObjectProvider<>() {
            @Override
            public SemanticPagingManager getObject(Object... args) {
                return null;
            }

            @Override
            public SemanticPagingManager getIfAvailable() {
                return null;
            }

            @Override
            public SemanticPagingManager getIfUnique() {
                return null;
            }

            @Override
            public SemanticPagingManager getObject() {
                return null;
            }

            @Override
            public Iterator<SemanticPagingManager> iterator() {
                return Collections.emptyIterator();
            }
        };
    }

    private static ObjectProvider<EmbeddingService> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public EmbeddingService getObject(Object... args) {
                return null;
            }

            @Override
            public EmbeddingService getIfAvailable() {
                return null;
            }

            @Override
            public EmbeddingService getIfUnique() {
                return null;
            }

            @Override
            public EmbeddingService getObject() {
                return null;
            }

            @Override
            public Iterator<EmbeddingService> iterator() {
                return Collections.emptyIterator();
            }
        };
    }

    private static final class BlockingExtractionService extends MemoryExtractionService {
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final String blockedContent;

        private BlockingExtractionService(CountDownLatch started, CountDownLatch release) {
            this(started, release, "running content");
        }

        private BlockingExtractionService(
                CountDownLatch started,
                CountDownLatch release,
                String blockedContent) {
            super(12);
            this.started = started;
            this.release = release;
            this.blockedContent = blockedContent;
        }

        @Override
        public ExtractionResult extract(String content) {
            if (!blockedContent.equals(content)) {
                return super.extract(content);
            }
            started.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release extraction");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return super.extract(content);
        }
    }

    private static final class FixedEmbeddingService implements EmbeddingService {
        private final int dimension;

        private FixedEmbeddingService(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public float[] embed(String text) {
            float[] vector = new float[dimension];
            vector[0] = 1.0f;
            return vector;
        }

        @Override
        public int dimension() {
            return dimension;
        }
    }

    private static class TestL2WarmStore implements L2WarmStore {
        private final int dimension;
        private final Map<String, MemoryFragment> fragmentsById = new ConcurrentHashMap<>();

        private TestL2WarmStore(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public void upsert(MemoryFragment fragment) {
            fragmentsById.put(fragment.getId(), fragment);
        }

        @Override
        public List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK) {
            return listByNamespace(namespace, topK);
        }

        @Override
        public Optional<MemoryFragment> get(String id) {
            return Optional.ofNullable(fragmentsById.get(id));
        }

        @Override
        public List<MemoryFragment> listByNamespace(String namespace, int limit) {
            return fragmentsById.values().stream()
                    .filter(fragment -> namespace.equals(fragment.getNamespace()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void delete(String id) {
            fragmentsById.remove(id);
        }

        @Override
        public int vectorDimension() {
            return dimension;
        }
    }

    private static final class FailingL2WarmStore extends TestL2WarmStore {
        private FailingL2WarmStore(int dimension) {
            super(dimension);
        }

        @Override
        public void upsert(MemoryFragment fragment) {
            throw new IllegalStateException("simulated L2 failure");
        }
    }

    private static final class TestL3ColdStore implements L3ColdStore {
        private final Map<String, MemoryFragment> fragmentsById = new ConcurrentHashMap<>();

        @Override
        public void archiveFragment(MemoryFragment fragment) {
            fragmentsById.put(fragment.getId(), fragment);
        }

        @Override
        public Optional<MemoryFragment> retrieveFragment(String id) {
            return Optional.ofNullable(fragmentsById.get(id));
        }

        @Override
        public void deleteFragment(String id) {
            fragmentsById.remove(id);
        }

        @Override
        public String saveCheckpoint(TaskState state) {
            return "checkpoint";
        }

        @Override
        public CheckpointMetadata saveCheckpointWithMetadata(TaskState state, CheckpointMetadata meta) {
            return meta;
        }

        @Override
        public Optional<TaskState> loadCheckpoint(String checkpointRef) {
            return Optional.empty();
        }

        @Override
        public void deleteCheckpoint(String checkpointRef) {
        }

        @Override
        public Set<String> listTaskIdsWithCheckpoints() {
            return Set.of();
        }
    }
}
