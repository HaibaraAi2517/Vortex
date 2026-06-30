package com.vortex.app.eval;

import com.vortex.common.dto.RecallDiagnostics;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.generation.PromptAssembler;
import com.vortex.kernel.generation.PromptAssemblyRequest;
import com.vortex.kernel.generation.PromptAssemblyResult;
import com.vortex.kernel.hmc.AsyncMemoryPipeline;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.hmc.MemoryPipelineRequest;
import com.vortex.kernel.hmc.MemoryPipelineStage;
import com.vortex.kernel.hmc.MemoryPipelineStatus;
import com.vortex.kernel.hmc.MemoryPipelineStatusCode;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncPipelineLatencyBenchmarkRunnerTest {

    private final AsyncMemoryPipeline pipeline = mock(AsyncMemoryPipeline.class);
    private final HierarchicalMemoryController hmc = mock(HierarchicalMemoryController.class);
    private final PromptAssembler promptAssembler = mock(PromptAssembler.class);
    private final TestL1HotStore l1 = new TestL1HotStore();
    private final SearchOnlyL2WarmStore l2 = new SearchOnlyL2WarmStore();
    private final TestL3ColdStore l3 = new TestL3ColdStore();
    private final LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
    private AsyncPipelineLatencyBenchmarkRunner runner;

    @BeforeEach
    void setUp() {
        l1.clearAll();
        l2.clearAll();
        l3.clearAll();
        properties.setAsyncPipelineBenchmarkFragments(4);
        properties.setAsyncPipelineBenchmarkWarmupFragments(0);
        properties.setRecoveryPollTimeout(Duration.ofSeconds(2));
        properties.setRecoveryPollInterval(Duration.ofMillis(1));
        properties.setRecallTopK(3);
        properties.setRecallTokenBudget(512);
        properties.setMaxPromptTokens(1024);
        runner = new AsyncPipelineLatencyBenchmarkRunner(pipeline, hmc, promptAssembler, l1, l2, l3, properties);

        when(pipeline.queueSnapshot()).thenAnswer(invocation -> queueSnapshot(0, 8, 0));
        when(hmc.recall(any(RecallQuery.class))).thenAnswer(invocation -> recallResult(invocation.getArgument(0)));
        when(promptAssembler.assemble(any(PromptAssemblyRequest.class))).thenAnswer(invocation -> {
            PromptAssemblyRequest request = invocation.getArgument(0);
            List<String> ids = request.recallResult().getFragments().stream()
                    .map(RecallResult.ScoredFragment::getFragment)
                    .map(MemoryFragment::getId)
                    .toList();
            return new PromptAssemblyResult("system", "user", 96, ids, List.of());
        });
        when(pipeline.processBlocking(any(MemoryPipelineRequest.class))).thenAnswer(invocation -> {
            MemoryPipelineRequest request = invocation.getArgument(0);
            pause(16L);
            return complete(request);
        });
        when(pipeline.submit(any(MemoryPipelineRequest.class))).thenAnswer(invocation -> {
            MemoryPipelineRequest request = invocation.getArgument(0);
            MemoryPipelineStatus accepted = MemoryPipelineStatus.builder()
                    .pipelineId(request.getPipelineId())
                    .namespace(request.getNamespace())
                    .status(MemoryPipelineStatusCode.ACCEPTED)
                    .acceptedAt(Instant.now())
                    .completedStages(List.of(MemoryPipelineStage.ADMISSION))
                    .fragmentIds(List.of())
                    .build();
            MemoryPipelineStatus completed = complete(request);
            when(pipeline.snapshot(request.getPipelineId())).thenReturn(Optional.of(completed));
            return accepted;
        });
    }

    @Test
    void runConfiguredBenchmarkShouldCompareRealMainPathWithSyncAndAsyncMemoryWrite() {
        AsyncPipelineLatencyBenchmarkReport report = runner.runConfiguredBenchmark();

        assertThat(report.getMainPathScope())
                .contains("hybrid retrieval")
                .contains("prompt/context assembly")
                .contains("excludes external LLM generation");
        assertThat(report.getAsyncPipelineScope()).contains("L2 index + L3 archive readiness");
        assertThat(report.getSuccessDefinition()).contains("main-path recall/rerank/prompt assembly");
        assertThat(report.getRandomSeed()).isEqualTo(AsyncPipelineLatencyBenchmarkRunner.RANDOM_SEED);
        assertThat(report.getModes()).containsExactly("SYNC_BASELINE", "ASYNC_PIPELINE");
        assertThat(report.getFragmentCount()).isEqualTo(4);
        assertThat(report.getResults()).hasSize(8);
        assertThat(report.getBackpressureSummary().getPolicy()).isEqualTo("CALLER_RUNS");
        assertThat(report.getBackpressureSummary().getProbeSubmittedCount()).isGreaterThan(0);
        assertThat(report.getBackpressureSummary().getProbeCompletedCount())
                .isEqualTo(report.getBackpressureSummary().getProbeSubmittedCount());
        assertThat(report.getBackpressureSummary().getProbeErrorCount()).isZero();
        assertThat(report.getModeSummaries().get("SYNC_BASELINE").getMainPathSuccessRate()).isEqualTo(1.0d);
        assertThat(report.getModeSummaries().get("ASYNC_PIPELINE").getMainPathSuccessRate()).isEqualTo(1.0d);
        assertThat(report.getModeSummaries().get("SYNC_BASELINE").getPersistenceSuccessRate()).isEqualTo(1.0d);
        assertThat(report.getModeSummaries().get("ASYNC_PIPELINE").getPersistenceSuccessRate()).isEqualTo(1.0d);
        assertThat(report.getModeSummaries().get("ASYNC_PIPELINE").getRecallSuccesses()).isEqualTo(4);
        assertThat(report.getModeSummaries().get("ASYNC_PIPELINE").getPromptAssemblySuccesses()).isEqualTo(4);
        assertThat(report.getModeSummaries().get("ASYNC_PIPELINE").getRerankCandidateAverage()).isGreaterThan(0.0d);
        assertThat(report.getSyncAverageMainPathLatencyMs())
                .isGreaterThan(report.getAsyncAverageMainPathLatencyMs());
        assertThat(report.getRelativeMainPathLatencyReduction()).isGreaterThan(0.0d);
    }

    @Test
    void summarizeModeShouldComputeMainPathPipelineAndBackpressureMetrics() {
        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results = List.of(
                result("SYNC_BASELINE", 10.0d, 4.0d, 1.0d, 20.0d, true),
                result("SYNC_BASELINE", 20.0d, 5.0d, 1.5d, 30.0d, true),
                result("SYNC_BASELINE", 30.0d, 6.0d, 2.0d, 40.0d, true),
                result("SYNC_BASELINE", 40.0d, 7.0d, 2.5d, 50.0d, false));

        AsyncPipelineLatencyBenchmarkReport.ModeSummary summary =
                AsyncPipelineLatencyBenchmarkRunner.summarizeMode(results);

        assertThat(summary.getTotal()).isEqualTo(4);
        assertThat(summary.getSuccesses()).isEqualTo(3);
        assertThat(summary.getMainPathSuccesses()).isEqualTo(4);
        assertThat(summary.getRecallSuccesses()).isEqualTo(4);
        assertThat(summary.getPromptAssemblySuccesses()).isEqualTo(4);
        assertThat(summary.getErrors()).isEqualTo(1);
        assertThat(summary.getPersistenceSuccessRate()).isEqualTo(0.75d);
        assertThat(summary.getMainPathSuccessRate()).isEqualTo(1.0d);
        assertThat(summary.getMainPathLatencyP50Ms()).isEqualTo(20.0d);
        assertThat(summary.getMainPathLatencyP95Ms()).isEqualTo(40.0d);
        assertThat(summary.getRecallLatencyAverageMs()).isEqualTo(5.5d);
        assertThat(summary.getPromptAssemblyLatencyAverageMs()).isEqualTo(1.75d);
        assertThat(summary.getAsyncPipelineLatencyAverageMs()).isEqualTo(35.0d);
        assertThat(summary.getAsyncPipelineThroughputPerSecond()).isGreaterThan(0.0d);
    }

    @Test
    void backpressureSummaryShouldReportCallerRunsAndSaturation() {
        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results = List.of(
                AsyncPipelineLatencyBenchmarkReport.CaseResult.builder()
                        .mode("ASYNC_PIPELINE")
                        .queueSizeBefore(1)
                        .queueSizeAfter(4)
                        .callerRunsCountBefore(2)
                        .callerRunsCountAfter(3)
                        .build());

        AsyncPipelineLatencyBenchmarkReport.BackpressureSummary summary =
                AsyncPipelineLatencyBenchmarkRunner.buildBackpressureSummary(queueSnapshot(4, 4, 3), results);

        assertThat(summary.getPolicy()).isEqualTo("CALLER_RUNS");
        assertThat(summary.getMaxObservedQueueSize()).isEqualTo(4);
        assertThat(summary.getCallerRunsCountDuringBenchmark()).isEqualTo(1);
        assertThat(summary.isSaturated()).isTrue();
    }

    @Test
    void relativeReductionShouldCompareAgainstSyncBaseline() {
        assertThat(AsyncPipelineLatencyBenchmarkRunner.relativeReduction(40.0d, 10.0d)).isEqualTo(0.75d);
        assertThat(AsyncPipelineLatencyBenchmarkRunner.relativeReduction(0.0d, 10.0d)).isZero();
    }

    private RecallResult recallResult(RecallQuery query) {
        String service = query.getTags().stream()
                .filter(tag -> tag.startsWith("service-"))
                .findFirst()
                .orElse("service-1");
        MemoryFragment fragment = MemoryFragment.builder()
                .id("recall-" + service)
                .namespace(query.getNamespace())
                .content("Current routing owner: " + service + "-owner. Current deployment guardrail: "
                        + service + " requires replay verification before promotion.")
                .embedding(new float[] {1.0f, 0.0f, 0.0f, 0.0f})
                .tokenCount(24)
                .tags(List.of("main-path-latency-benchmark", service))
                .build();
        RecallDiagnostics diagnostics = RecallDiagnostics.builder()
                .l1CandidateCount(4)
                .l1TagMatchedCount(1)
                .l1SelectedCount(1)
                .l2SearchCandidateCount(2)
                .l2SearchAcceptedCount(1)
                .keywordCandidateCount(4)
                .keywordAcceptedCount(1)
                .rerankCandidateCount(4)
                .finalReturnedCount(1)
                .build();
        return RecallResult.builder()
                .fragments(List.of(RecallResult.ScoredFragment.builder()
                        .fragment(fragment)
                        .score(0.99d)
                        .tier("L1")
                        .build()))
                .totalTokens(fragment.getTokenCount())
                .sourceTrace(List.of("L1"))
                .recallSessionId("session-" + service)
                .diagnostics(diagnostics)
                .build();
    }

    private MemoryPipelineStatus complete(MemoryPipelineRequest request) {
        String fragmentId = request.getPipelineId() + "::fragment-1";
        MemoryFragment fragment = MemoryFragment.builder()
                .id(fragmentId)
                .namespace(request.getNamespace())
                .content("summary " + request.getContent())
                .embedding(new float[] {1.0f, 0.0f, 0.0f, 0.0f})
                .l2Embedding(new float[] {1.0f, 0.0f, 0.0f, 0.0f})
                .tokenCount(12)
                .createdAt(Instant.now())
                .build();
        l1.put(fragment);
        l2.upsert(fragment);
        l3.archiveFragment(fragment);
        Instant startedAt = Instant.now().minusMillis(12);
        return MemoryPipelineStatus.builder()
                .pipelineId(request.getPipelineId())
                .namespace(request.getNamespace())
                .status(MemoryPipelineStatusCode.COMPLETED)
                .acceptedAt(startedAt.minusMillis(1))
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .completedStages(List.of(
                        MemoryPipelineStage.ADMISSION,
                        MemoryPipelineStage.EXTRACTION,
                        MemoryPipelineStage.SUMMARY,
                        MemoryPipelineStage.SPLIT,
                        MemoryPipelineStage.EMBEDDING,
                        MemoryPipelineStage.L1_ADMISSION,
                        MemoryPipelineStage.L2_INDEX,
                        MemoryPipelineStage.L3_ARCHIVE))
                .fragmentIds(List.of(fragmentId))
                .extractedUnitCount(4)
                .summaryTokenCount(24)
                .fragmentCount(1)
                .build();
    }

    private AsyncPipelineLatencyBenchmarkReport.CaseResult result(
            String mode,
            double mainPathLatencyMs,
            double recallLatencyMs,
            double promptLatencyMs,
            double asyncPipelineLatencyMs,
            boolean persistenceSucceeded) {
        return AsyncPipelineLatencyBenchmarkReport.CaseResult.builder()
                .caseId(mode + "-" + mainPathLatencyMs)
                .mode(mode)
                .pipelineId("pipeline-" + mainPathLatencyMs)
                .fragmentId("fragment-" + mainPathLatencyMs)
                .fragmentIds(List.of("fragment-" + mainPathLatencyMs))
                .namespace("ns")
                .pipelineStatus(persistenceSucceeded ? "COMPLETED" : "FAILED")
                .completedStages(List.of("EXTRACTION", "SUMMARY", "EMBEDDING", "L1_ADMISSION", "L2_INDEX", "L3_ARCHIVE"))
                .mainPathLatencyMs(mainPathLatencyMs)
                .recallLatencyMs(recallLatencyMs)
                .promptAssemblyLatencyMs(promptLatencyMs)
                .memoryWriteSubmissionLatencyMs(promptLatencyMs + 1.0d)
                .asyncPipelineLatencyMs(asyncPipelineLatencyMs)
                .readinessLatencyMs(mainPathLatencyMs + 10.0d)
                .returnedFragmentCount(1)
                .rerankCandidateCount(4)
                .recallSucceeded(true)
                .promptAssemblySucceeded(true)
                .mainPathSucceeded(true)
                .l2Ready(persistenceSucceeded)
                .l3Ready(persistenceSucceeded)
                .persistenceSucceeded(persistenceSucceeded)
                .errorMessage(persistenceSucceeded ? null : "simulated")
                .build();
    }

    private AsyncMemoryPipeline.PipelineQueueSnapshot queueSnapshot(int queueSize, int capacity, long callerRuns) {
        return new AsyncMemoryPipeline.PipelineQueueSnapshot(0, 2, 2, queueSize,
                Math.max(0, capacity - queueSize), capacity, callerRuns, "CALLER_RUNS");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class TestL1HotStore implements L1HotStore {
        private final Map<String, MemoryFragment> fragmentsById = new ConcurrentHashMap<>();

        @Override
        public void put(MemoryFragment fragment) {
            fragmentsById.put(fragment.getId(), fragment);
        }

        @Override
        public void put(MemoryFragment fragment, boolean recordAccess) {
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

        void clearAll() {
            fragmentsById.clear();
        }
    }

    private static class TestL2WarmStore implements L2WarmStore {
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

        void clearAll() {
            fragmentsById.clear();
        }
    }

    private static final class SearchOnlyL2WarmStore extends TestL2WarmStore {
        @Override
        public Optional<MemoryFragment> get(String id) {
            return Optional.empty();
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

        void clearAll() {
            fragmentsById.clear();
        }
    }
}