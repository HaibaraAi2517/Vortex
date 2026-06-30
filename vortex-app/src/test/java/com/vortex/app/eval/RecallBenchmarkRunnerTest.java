package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.RecallDiagnostics;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.dto.RetrievalMode;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.serialization.JsonMapperFactory;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecallBenchmarkRunnerTest {

    private final HierarchicalMemoryController hmc = mock(HierarchicalMemoryController.class);
    private final TestL1HotStore l1 = new TestL1HotStore();
    private final TestL2WarmStore l2 = new TestL2WarmStore();
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
    private final ObjectMapper objectMapper = JsonMapperFactory.create();
    private final LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
    private final TokenCounter tokenCounter = text -> text == null || text.isBlank()
            ? 0
            : text.trim().split("\\s+").length;
    private RecallBenchmarkRunner runner;

    @BeforeEach
    void setUp() {
        l1.clearAll();
        l2.clearAll();
        properties.setDatasetLocation("classpath:llm-memory-eval-set.json");
        properties.setRecallTopK(2);
        properties.setRecallTokenBudget(512);
        properties.setRecoveryPollTimeout(Duration.ofMillis(50));
        properties.setRecoveryPollInterval(Duration.ofMillis(1));
        properties.setModes(List.of(LlmMemoryEvalMode.VORTEX_VECTOR_ONLY, LlmMemoryEvalMode.VORTEX_MEMORY));
        runner = new RecallBenchmarkRunner(
                hmc,
                l2,
                resourceLoader,
                objectMapper,
                properties,
                tokenCounter);

        when(hmc.getL1()).thenReturn(l1);
        doAnswer(invocation -> {
            MemoryFragment fragment = invocation.getArgument(0);
            l1.put(fragment);
            l2.upsert(fragment);
            return null;
        }).when(hmc).storeFragment(any(MemoryFragment.class));
        doAnswer(invocation -> {
            String fragmentId = invocation.getArgument(0);
            l1.remove(fragmentId);
            l2.delete(fragmentId);
            return true;
        }).when(hmc).deleteFragment(any(String.class));
        when(hmc.recall(any(RecallQuery.class))).thenAnswer(invocation -> recall(invocation.getArgument(0)));
    }

    @Test
    void runShouldReportHybridRecallLiftAgainstVectorOnly() {
        LlmMemoryEvalCase evalCase = keywordLiftCase();

        RecallBenchmarkReport report = runner.run(List.of(evalCase), List.of(
                LlmMemoryEvalMode.VORTEX_VECTOR_ONLY,
                LlmMemoryEvalMode.VORTEX_MEMORY));

        assertThat(report.getTotalCases()).isEqualTo(1);
        assertThat(report.getTotalRuns()).isEqualTo(2);
        assertThat(report.getResults())
                .extracting(RecallBenchmarkReport.CaseResult::getMode)
                .containsExactly("Vector+Rerank", "Hybrid+Rerank");
        RecallBenchmarkReport.ModeSummary vectorOnly = report.getModeSummaries().get("Vector+Rerank");
        RecallBenchmarkReport.ModeSummary hybrid = report.getModeSummaries().get("Hybrid+Rerank");
        assertThat(vectorOnly.getRecallAtK()).isZero();
        assertThat(hybrid.getRecallAtK()).isEqualTo(1.0d);
        assertThat(hybrid.getRecallAtKLiftVsVectorOnly()).isEqualTo(1.0d);
        assertThat(hybrid.getCaseHitRateLiftVsVectorOnly()).isEqualTo(1.0d);
        assertThat(hybrid.getMetricsByK()).containsKeys(1, 2, 3, 5, 10);
        assertThat(report.getResults().stream()
                .filter(result -> "Hybrid+Rerank".equals(result.getMode()))
                .findFirst()
                .orElseThrow()
                .getReturnedFragmentIds()).contains("exact-owner");
    }

    @Test
    void runAblationShouldIncludeKeywordVectorHybridAndRerankVariants() {
        RecallBenchmarkReport report = runner.runAblation(List.of(keywordLiftCase()), List.of(
                RecallAblationMode.KEYWORD_ONLY,
                RecallAblationMode.VECTOR_ONLY,
                RecallAblationMode.VECTOR_RERANK,
                RecallAblationMode.HYBRID,
                RecallAblationMode.HYBRID_RERANK));

        assertThat(report.getModes()).containsExactly(
                "KeywordOnly",
                "VectorOnly",
                "Vector+Rerank",
                "Hybrid",
                "Hybrid+Rerank");
        assertThat(report.getTotalRuns()).isEqualTo(5);
        assertThat(report.getResults())
                .extracting(RecallBenchmarkReport.CaseResult::isRerankEnabled)
                .containsExactly(false, false, true, false, true);
    }

    @Test
    void runShouldBenchmarkCasesAgainstSharedNamespaceCandidatePool() {
        LlmMemoryEvalCase firstCase = LlmMemoryEvalCase.builder()
                .caseId("shared-001")
                .namespace("recall-benchmark-shared")
                .memoryFragments(List.of(
                        LlmMemoryEvalCase.EvalMemoryFragment.builder()
                                .fragmentId("shared-first-target")
                                .content("Atlas alpha owner is mira@example.com")
                                .tags(List.of("shared"))
                                .build(),
                        LlmMemoryEvalCase.EvalMemoryFragment.builder()
                                .fragmentId("shared-first-distractor")
                                .content("Atlas alpha old routing note")
                                .tags(List.of("shared"))
                                .build()))
                .question("Who owns Atlas alpha?")
                .expectedAnswer("mira@example.com")
                .expectedFragments(List.of("shared-first-target"))
                .tags(List.of("shared"))
                .build();
        LlmMemoryEvalCase secondCase = LlmMemoryEvalCase.builder()
                .caseId("shared-002")
                .namespace("recall-benchmark-shared")
                .memoryFragments(List.of(
                        LlmMemoryEvalCase.EvalMemoryFragment.builder()
                                .fragmentId("shared-second-target")
                                .content("Atlas beta owner is noah@example.com")
                                .tags(List.of("shared"))
                                .build(),
                        LlmMemoryEvalCase.EvalMemoryFragment.builder()
                                .fragmentId("shared-second-distractor")
                                .content("Atlas beta old routing note")
                                .tags(List.of("shared"))
                                .build()))
                .question("Who owns Atlas beta?")
                .expectedAnswer("noah@example.com")
                .expectedFragments(List.of("shared-second-target"))
                .tags(List.of("shared"))
                .build();

        RecallBenchmarkReport report = runner.run(List.of(firstCase, secondCase), List.of(
                LlmMemoryEvalMode.VORTEX_VECTOR_ONLY));

        assertThat(report.getResults()).hasSize(2);
        assertThat(report.getResults())
                .extracting(RecallBenchmarkReport.CaseResult::getNamespace)
                .containsOnly(report.getResults().getFirst().getNamespace());
        assertThat(report.getResults())
                .extracting(result -> result.getRecallDiagnostics().getVectorCandidateCount())
                .containsOnly(4);
    }

    @Test
    void runShouldLoadDefaultDatasetAndFilterToMemoryBackedModes() {
        properties.setModes(List.of(
                LlmMemoryEvalMode.BASELINE_NO_MEMORY,
                LlmMemoryEvalMode.VORTEX_VECTOR_ONLY,
                LlmMemoryEvalMode.VORTEX_MEMORY,
                LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));
        List<LlmMemoryEvalCase> cases = runner.loadCaseSet("classpath:llm-memory-eval-set.json").subList(0, 1);

        RecallBenchmarkReport report = runner.run(cases, properties.getModes());

        assertThat(report.getModes()).containsExactly("Vector+Rerank", "Hybrid+Rerank");
        assertThat(report.getResults())
                .allSatisfy(result -> assertThat(result.getMode()).isNotEqualTo("Baseline-NoMemory"));
    }
    @Test
    void precisionAtKShouldUseConfiguredKWhenFewerFragmentsAreReturned() {
        LlmMemoryEvalCase evalCase = LlmMemoryEvalCase.builder()
                .caseId("precision-k-001")
                .namespace("recall-benchmark-precision")
                .memoryFragments(List.of(LlmMemoryEvalCase.EvalMemoryFragment.builder()
                        .fragmentId("only-target")
                        .content("Single returned owner is avery-deploy@example.com")
                        .tags(List.of("precision"))
                        .build()))
                .question("Which owner should be used?")
                .expectedAnswer("avery-deploy@example.com")
                .expectedFragments(List.of("only-target"))
                .tags(List.of("precision"))
                .build();

        RecallBenchmarkReport report = runner.runAblation(List.of(evalCase), List.of(RecallAblationMode.KEYWORD_ONLY));

        RecallBenchmarkReport.CaseResult result = report.getResults().getFirst();
        assertThat(result.getMetricsByK().get(5).getRecall()).isEqualTo(1.0d);
        assertThat(result.getMetricsByK().get(5).getPrecision()).isEqualTo(0.2d);
        assertThat(report.getModeSummaries().get("KeywordOnly").getMetricsByK().get(5).getPrecision()).isEqualTo(0.2d);
    }

    private LlmMemoryEvalCase keywordLiftCase() {
        return LlmMemoryEvalCase.builder()
                .caseId("keyword-lift-001")
                .namespace("recall-benchmark-test")
                .memoryFragments(List.of(
                        LlmMemoryEvalCase.EvalMemoryFragment.builder()
                                .fragmentId("exact-owner")
                                .content("Pegasus owner is avery-deploy@example.com")
                                .tags(List.of("owner"))
                                .build(),
                        LlmMemoryEvalCase.EvalMemoryFragment.builder()
                                .fragmentId("semantic-distractor")
                                .content("Pegasus deployment notes describe a backup contact list.")
                                .tags(List.of("owner"))
                                .build()))
                .question("Which Pegasus owner email should be used?")
                .expectedAnswer("avery-deploy@example.com")
                .expectedFragments(List.of("exact-owner"))
                .tags(List.of("owner"))
                .build();
    }

    private RecallResult recall(RecallQuery query) {
        List<MemoryFragment> namespaceFragments = l2.listByNamespace(query.getNamespace(), 100);
        List<MemoryFragment> returned = namespaceFragments.stream()
                .filter(fragment -> matchesTags(fragment, query.getTags()))
                .filter(fragment -> query.getRetrievalMode() != RetrievalMode.VECTOR_ONLY
                        || !fragment.getContent().contains("avery-deploy@example.com"))
                .filter(fragment -> query.getRetrievalMode() != RetrievalMode.KEYWORD_ONLY
                        || fragment.getContent().contains("avery-deploy@example.com"))
                .sorted(Comparator.comparing(MemoryFragment::getId))
                .limit(query.getTopK())
                .toList();
        return RecallResult.builder()
                .fragments(returned.stream()
                        .map(fragment -> RecallResult.ScoredFragment.builder()
                                .fragment(fragment)
                                .tier("L2")
                                .score(0.9d)
                                .build())
                        .toList())
                .sourceTrace(returned.stream().map(ignored -> "L2").toList())
                .totalTokens(returned.stream().mapToInt(MemoryFragment::getTokenCount).sum())
                .recallSessionId("recall-" + query.getRetrievalMode())
                .diagnostics(RecallDiagnostics.builder()
                        .retrievalMode(query.getRetrievalMode().name())
                        .rerankEnabled(query.isRerankEnabled())
                        .requiredTags(query.getTags())
                        .keywordCandidateCount(query.getRetrievalMode() == RetrievalMode.VECTOR_ONLY ? 0 : returned.size())
                        .keywordAcceptedCount(query.getRetrievalMode() == RetrievalMode.VECTOR_ONLY ? 0 : returned.size())
                        .vectorCandidateCount(query.getRetrievalMode() == RetrievalMode.KEYWORD_ONLY ? 0 : namespaceFragments.size())
                        .vectorAcceptedCount(query.getRetrievalMode() == RetrievalMode.KEYWORD_ONLY ? 0 : returned.size())
                        .rerankCandidateCount(query.isRerankEnabled() ? returned.size() : 0)
                        .l2SearchCandidateCount(namespaceFragments.size())
                        .l2SearchAcceptedCount(returned.size())
                        .finalReturnedCount(returned.size())
                        .build())
                .build();
    }

    private boolean matchesTags(MemoryFragment fragment, List<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) {
            return true;
        }
        List<String> fragmentTags = fragment.getTags();
        return fragmentTags != null && fragmentTags.containsAll(requiredTags);
    }

    private static final class TestL1HotStore implements L1HotStore {
        private final Map<String, MemoryFragment> fragmentsById = new ConcurrentHashMap<>();

        @Override
        public void put(MemoryFragment fragment) {
            fragmentsById.put(fragment.getId(), fragment);
        }

        @Override
        public Optional<MemoryFragment> get(String id) {
            return Optional.ofNullable(fragmentsById.get(id));
        }

        @Override
        public Optional<MemoryFragment> peek(String id) {
            return get(id);
        }

        @Override
        public List<MemoryFragment> getAll(String namespace) {
            return fragmentsById.values().stream()
                    .filter(fragment -> namespace.equals(fragment.getNamespace()))
                    .toList();
        }

        @Override
        public void remove(String id) {
            fragmentsById.remove(id);
        }

        @Override
        public long currentTokenCount() {
            return fragmentsById.values().stream().mapToLong(MemoryFragment::getTokenCount).sum();
        }

        @Override
        public long maxTokenCapacity() {
            return 1024L;
        }

        @Override
        public void clear(String namespace) {
            fragmentsById.entrySet().removeIf(entry -> namespace.equals(entry.getValue().getNamespace()));
        }

        private void clearAll() {
            fragmentsById.clear();
        }
    }

    private static final class TestL2WarmStore implements L2WarmStore {
        private final Map<String, MemoryFragment> fragmentsById = new ConcurrentHashMap<>();

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

        private void clearAll() {
            fragmentsById.clear();
        }
    }
}