package com.vortex.app.eval;

import com.vortex.common.dto.RecallDiagnostics;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.dto.RerankerType;
import com.vortex.common.dto.RetrievalMode;
import com.vortex.common.model.MemoryFragment;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPipelineLatencyBenchmarkRunner {

    static final String SYNC_MODE = "SYNC_BASELINE";
    static final String ASYNC_MODE = "ASYNC_PIPELINE";
    static final long RANDOM_SEED = 20260629L;
    static final String MAIN_PATH_SCOPE = "request -> hybrid retrieval -> rerank -> prompt/context assembly -> return payload; "
            + "excludes external LLM generation; sync mode waits for the full write, while async mode waits for raw-memory "
            + "L1 write-through admission; each mode/case uses an isolated namespace";
    static final String ASYNC_PIPELINE_SCOPE = "synchronous raw-memory L1 write-through at admission; background memory "
            + "extraction + summary + semantic split + embedding + processed L1 admission + L2 index + L3 archive readiness";
    static final String SUCCESS_DEFINITION = "A case passes when main-path recall/rerank/prompt assembly returns at least one "
            + "expected benchmark memory, the submitted write is visible in L1 at return, and the mode-specific memory write "
            + "reaches L2/L3 readiness without errors.";

    private final AsyncMemoryPipeline memoryPipeline;
    private final HierarchicalMemoryController hmc;
    private final PromptAssembler promptAssembler;
    private final L1HotStore l1;
    private final L2WarmStore l2;
    private final L3ColdStore l3;
    private final LlmMemoryEvalProperties properties;

    public AsyncPipelineLatencyBenchmarkReport runConfiguredBenchmark() {
        int caseCount = Math.max(1, properties.getAsyncPipelineBenchmarkFragments());
        int warmupCount = Math.max(0, properties.getAsyncPipelineBenchmarkWarmupFragments());
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String namespace = "main-path-latency-benchmark-" + runId;

        log.info("Running main-path async memory latency benchmark runId={} namespace={} cases={} warmup={}",
                runId, namespace, caseCount, warmupCount);

        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> warmupResults = runWarmup(namespace, runId, warmupCount);
        cleanup(warmupResults);

        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results = new ArrayList<>();
        results.addAll(runSyncCases(namespace, runId, caseCount));
        results.addAll(runAsyncCases(namespace, runId, caseCount));

        Map<String, AsyncPipelineLatencyBenchmarkReport.ModeSummary> summaries = buildModeSummaries(results);
        AsyncPipelineLatencyBenchmarkReport.ModeSummary syncSummary = summaries.get(SYNC_MODE);
        AsyncPipelineLatencyBenchmarkReport.ModeSummary asyncSummary = summaries.get(ASYNC_MODE);
        double syncAverage = syncSummary == null ? 0.0d : syncSummary.getMainPathLatencyAverageMs();
        double asyncAverage = asyncSummary == null ? 0.0d : asyncSummary.getMainPathLatencyAverageMs();
        double asyncPersistenceSuccessRate = asyncSummary == null ? 0.0d : asyncSummary.getPersistenceSuccessRate();
        AsyncPipelineLatencyBenchmarkReport.BackpressureSummary backpressure = runBackpressureProbe(
                namespace, runId, results);

        return AsyncPipelineLatencyBenchmarkReport.builder()
                .generatedAt(Instant.now())
                .runId(runId)
                .randomSeed(RANDOM_SEED)
                .mainPathScope(MAIN_PATH_SCOPE)
                .asyncPipelineScope(ASYNC_PIPELINE_SCOPE)
                .successDefinition(SUCCESS_DEFINITION)
                .benchmarkScope(MAIN_PATH_SCOPE + "; async readiness scope: " + ASYNC_PIPELINE_SCOPE)
                .fragmentCount(caseCount)
                .warmupFragmentCount(warmupCount)
                .modes(List.of(SYNC_MODE, ASYNC_MODE))
                .syncAverageMainPathLatencyMs(syncAverage)
                .asyncAverageMainPathLatencyMs(asyncAverage)
                .relativeMainPathLatencyReduction(relativeReduction(syncAverage, asyncAverage))
                .persistenceSuccessRate(asyncPersistenceSuccessRate)
                .modeSummaries(summaries)
                .backpressureSummary(backpressure)
                .results(List.copyOf(results))
                .build();
    }

    private void seedRecallCase(String namespace, String runId, int index) {
        String service = serviceName(index);
        MemoryFragment fragment = MemoryFragment.builder()
                .id("main-path-seed::%s::%03d".formatted(runId, index + 1))
                .namespace(namespace)
                .content("""
                        Benchmark memory seed %d for %s.
                        Current routing owner: %s-owner.
                        Current escalation queue: %s-critical.
                        Current deployment guardrail: %s requires replay verification before promotion.
                        """.formatted(index + 1, service, service, service, service))
                .embedding(embeddingFor(index))
                .l2Embedding(embeddingFor(index))
                .tokenCount(42)
                .importance(0.60d + (index % 5) * 0.05d)
                .tags(List.of("main-path-latency-benchmark", service))
                .reasoningChainId("main-path-latency-" + runId)
                .build();
        l1.put(fragment, false);
        l2.upsert(fragment);
        l3.archiveFragment(fragment);
    }

    private List<AsyncPipelineLatencyBenchmarkReport.CaseResult> runWarmup(
            String namespace,
            String runId,
            int warmupCount) {
        if (warmupCount == 0) {
            return List.of();
        }
        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results = new ArrayList<>();
        results.addAll(runSyncCases(namespace, "warmup-" + runId, warmupCount));
        results.addAll(runAsyncCases(namespace, "warmup-" + runId, warmupCount));
        return results;
    }

    private List<AsyncPipelineLatencyBenchmarkReport.CaseResult> runSyncCases(
            String namespace,
            String runId,
            int caseCount) {
        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results = new ArrayList<>();
        for (int index = 0; index < caseCount; index++) {
            results.add(runCase(SYNC_MODE, namespace, runId, index));
        }
        return results;
    }

    private List<AsyncPipelineLatencyBenchmarkReport.CaseResult> runAsyncCases(
            String namespace,
            String runId,
            int caseCount) {
        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results = new ArrayList<>();
        for (int index = 0; index < caseCount; index++) {
            results.add(runCase(ASYNC_MODE, namespace, runId, index));
        }
        return results;
    }
    private AsyncPipelineLatencyBenchmarkReport.CaseResult runCase(
            String mode,
            String namespace,
            String runId,
            int index) {
        String caseNamespace = "%s-%s-%s-%03d".formatted(
                namespace,
                runId,
                mode.toLowerCase().replace('_', '-'),
                index + 1);
        seedRecallCase(caseNamespace, runId + "-" + mode.toLowerCase(), index);
        MemoryPipelineRequest memoryWrite = buildRequest(mode, caseNamespace, runId, index);
        RecallQuery recallQuery = buildRecallQuery(caseNamespace, index);
        String question = buildQuestion(index);
        AsyncMemoryPipeline.PipelineQueueSnapshot beforeQueue = memoryPipeline.queueSnapshot();
        long startedAt = System.nanoTime();
        try {
            RecallResult recallResult = hmc.recall(recallQuery);
            double recallLatencyMs = elapsedMillis(startedAt);
            long assemblyStartedAt = System.nanoTime();
            PromptAssemblyResult prompt = promptAssembler.assemble(new PromptAssemblyRequest(
                    null,
                    question,
                    recallResult,
                    buildTaskContext(mode, index),
                    properties.getMaxPromptTokens()));
            double promptAssemblyLatencyMs = elapsedMillis(assemblyStartedAt);
            MemoryPipelineStatus writeStatus;
            MemoryPipelineStatus completed;
            double writeSubmissionLatencyMs;
            double mainPathLatencyMs;
            boolean writeThroughVisibleAtReturn;
            if (SYNC_MODE.equals(mode)) {
                long writeStartedAt = System.nanoTime();
                writeStatus = memoryPipeline.processBlocking(memoryWrite);
                completed = writeStatus;
                writeThroughVisibleAtReturn = fragmentsVisibleInL1(writeStatus);
                waitForReadiness(completed);
                writeSubmissionLatencyMs = elapsedMillis(writeStartedAt);
                mainPathLatencyMs = elapsedMillis(startedAt);
            } else {
                long writeStartedAt = System.nanoTime();
                writeStatus = memoryPipeline.submit(memoryWrite);
                writeSubmissionLatencyMs = elapsedMillis(writeStartedAt);
                mainPathLatencyMs = elapsedMillis(startedAt);
                writeThroughVisibleAtReturn = fragmentsVisibleInL1(writeStatus);
                completed = waitForCompletion(writeStatus.getPipelineId());
                waitForReadiness(completed);
            }
            double asyncPipelineLatencyMs = latencyBetween(completed.getStartedAt(), completed.getCompletedAt());
            double readinessLatencyMs = elapsedMillis(startedAt);
            boolean l2Ready = allL2Ready(completed);
            boolean l3Ready = allL3Ready(completed);
            boolean recallSucceeded = recallSucceeded(recallResult, index);
            boolean promptSucceeded = prompt.promptTokens() > 0 && !prompt.includedFragmentIds().isEmpty();
            boolean persistenceSucceeded = persistenceSucceeded(completed, l2Ready, l3Ready);
            AsyncMemoryPipeline.PipelineQueueSnapshot afterQueue = memoryPipeline.queueSnapshot();
            return caseBuilder(index, mode, completed)
                    .mainPathLatencyMs(mainPathLatencyMs)
                    .recallLatencyMs(recallLatencyMs)
                    .promptAssemblyLatencyMs(promptAssemblyLatencyMs)
                    .memoryWriteSubmissionLatencyMs(writeSubmissionLatencyMs)
                    .asyncPipelineLatencyMs(asyncPipelineLatencyMs)
                    .readinessLatencyMs(readinessLatencyMs)
                    .returnedFragmentCount(fragmentCount(recallResult))
                    .returnedTokenCount(recallResult == null ? 0 : recallResult.getTotalTokens())
                    .promptTokenCount(prompt.promptTokens())
                    .includedPromptFragmentCount(prompt.includedFragmentIds().size())
                    .omittedPromptFragmentCount(prompt.omittedFragmentIds().size())
                    .recallCandidateCount(recallCandidateCount(recallResult))
                    .rerankCandidateCount(rerankCandidateCount(recallResult))
                    .l1CandidateCount(l1CandidateCount(recallResult))
                    .l2SearchCandidateCount(l2SearchCandidateCount(recallResult))
                    .keywordCandidateCount(keywordCandidateCount(recallResult))
                    .recallSucceeded(recallSucceeded)
                    .promptAssemblySucceeded(promptSucceeded)
                    .writeThroughVisibleAtReturn(writeThroughVisibleAtReturn)
                    .l2Ready(l2Ready)
                    .l3Ready(l3Ready)
                    .persistenceSucceeded(persistenceSucceeded)
                    .mainPathSucceeded(recallSucceeded && promptSucceeded && writeThroughVisibleAtReturn)
                    .queueSizeBefore(beforeQueue.queueSize())
                    .queueSizeAfter(afterQueue.queueSize())
                    .queueCapacity(afterQueue.queueCapacity())
                    .queueRemainingCapacity(afterQueue.queueRemainingCapacity())
                    .callerRunsCountBefore(beforeQueue.callerRunsCount())
                    .callerRunsCountAfter(afterQueue.callerRunsCount())
                    .backpressurePolicy(afterQueue.backpressurePolicy())
                    .build();
        } catch (RuntimeException e) {
            return failedCase(index, mode, memoryWrite, startedAt, beforeQueue, e);
        }
    }

    private MemoryPipelineStatus waitForCompletion(String pipelineId) {
        waitForCondition(
                () -> memoryPipeline.snapshot(pipelineId)
                        .map(status -> status.getStatus() == MemoryPipelineStatusCode.COMPLETED
                                || status.getStatus() == MemoryPipelineStatusCode.FAILED)
                        .orElse(false),
                () -> "Timed out waiting for async memory pipeline completion pipelineId=" + pipelineId);
        MemoryPipelineStatus status = memoryPipeline.snapshot(pipelineId)
                .orElseThrow(() -> new IllegalStateException("Missing async memory pipeline status pipelineId=" + pipelineId));
        if (status.getStatus() != MemoryPipelineStatusCode.COMPLETED) {
            throw new IllegalStateException("Async memory pipeline failed pipelineId=" + pipelineId
                    + " error=" + nullToEmpty(status.getErrorMessage()));
        }
        return status;
    }

    private void waitForReadiness(MemoryPipelineStatus status) {
        waitForCondition(
                () -> allL2Ready(status) && allL3Ready(status),
                () -> "Timed out waiting for async memory pipeline L2/L3 readiness pipelineId=" + status.getPipelineId());
    }

    private void waitForCondition(BooleanSupplier condition, java.util.function.Supplier<String> timeoutMessage) {
        long timeoutMillis = Math.max(0L, properties.getRecoveryPollTimeout().toMillis());
        long deadline = System.currentTimeMillis() + timeoutMillis;
        if (condition.getAsBoolean()) {
            return;
        }
        long intervalMillis = Math.max(1L, properties.getRecoveryPollInterval().toMillis());
        while (System.currentTimeMillis() < deadline) {
            pause(Math.min(intervalMillis, Math.max(1L, deadline - System.currentTimeMillis())));
            if (condition.getAsBoolean()) {
                return;
            }
        }
        throw new IllegalStateException(timeoutMessage.get());
    }

    private void pause(long intervalMillis) {
        try {
            Thread.sleep(intervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for async memory pipeline benchmark readiness", e);
        }
    }

    private AsyncPipelineLatencyBenchmarkReport.CaseResult.CaseResultBuilder caseBuilder(
            int index,
            String mode,
            MemoryPipelineStatus status) {
        List<String> fragmentIds = status.getFragmentIds() == null ? List.of() : status.getFragmentIds();
        return AsyncPipelineLatencyBenchmarkReport.CaseResult.builder()
                .caseId("%s-%03d".formatted(mode.toLowerCase().replace('_', '-'), index + 1))
                .mode(mode)
                .pipelineId(status.getPipelineId())
                .fragmentId(fragmentIds.isEmpty() ? null : fragmentIds.getFirst())
                .fragmentIds(fragmentIds)
                .namespace(status.getNamespace())
                .pipelineStatus(status.getStatus() == null ? null : status.getStatus().name())
                .completedStages(stageNames(status))
                .extractedUnitCount(status.getExtractedUnitCount())
                .summaryTokenCount(status.getSummaryTokenCount())
                .fragmentCount(status.getFragmentCount());
    }

    private AsyncPipelineLatencyBenchmarkReport.CaseResult failedCase(
            int index,
            String mode,
            MemoryPipelineRequest request,
            long startedAt,
            AsyncMemoryPipeline.PipelineQueueSnapshot beforeQueue,
            RuntimeException e) {
        MemoryPipelineStatus status = memoryPipeline.snapshot(request.getPipelineId()).orElseGet(() -> MemoryPipelineStatus.builder()
                .pipelineId(request.getPipelineId())
                .namespace(request.getNamespace())
                .status(MemoryPipelineStatusCode.FAILED)
                .completedStages(List.of())
                .fragmentIds(List.of())
                .build());
        AsyncMemoryPipeline.PipelineQueueSnapshot afterQueue = memoryPipeline.queueSnapshot();
        log.warn("Main-path latency benchmark case failed mode={} pipelineId={}",
                mode, status.getPipelineId(), e);
        double elapsed = elapsedMillis(startedAt);
        return caseBuilder(index, mode, status)
                .mainPathLatencyMs(elapsed)
                .readinessLatencyMs(elapsed)
                .l2Ready(allL2Ready(status))
                .l3Ready(allL3Ready(status))
                .persistenceSucceeded(false)
                .mainPathSucceeded(false)
                .recallSucceeded(false)
                .promptAssemblySucceeded(false)
                .queueSizeBefore(beforeQueue.queueSize())
                .queueSizeAfter(afterQueue.queueSize())
                .queueCapacity(afterQueue.queueCapacity())
                .queueRemainingCapacity(afterQueue.queueRemainingCapacity())
                .callerRunsCountBefore(beforeQueue.callerRunsCount())
                .callerRunsCountAfter(afterQueue.callerRunsCount())
                .backpressurePolicy(afterQueue.backpressurePolicy())
                .errorMessage(e.getClass().getSimpleName() + ": " + nullToEmpty(e.getMessage()))
                .build();
    }
    private boolean persistenceSucceeded(MemoryPipelineStatus status, boolean l2Ready, boolean l3Ready) {
        return status != null
                && status.getStatus() == MemoryPipelineStatusCode.COMPLETED
                && hasStage(status, MemoryPipelineStage.EXTRACTION)
                && hasStage(status, MemoryPipelineStage.SUMMARY)
                && hasStage(status, MemoryPipelineStage.EMBEDDING)
                && hasStage(status, MemoryPipelineStage.L1_ADMISSION)
                && hasStage(status, MemoryPipelineStage.L2_INDEX)
                && hasStage(status, MemoryPipelineStage.L3_ARCHIVE)
                && l2Ready
                && l3Ready;
    }

    private boolean allL2Ready(MemoryPipelineStatus status) {
        List<String> fragmentIds = status == null || status.getFragmentIds() == null ? List.of() : status.getFragmentIds();
        return !fragmentIds.isEmpty() && fragmentIds.stream()
                .allMatch(fragmentId -> l2GetReady(status, fragmentId) || l2SearchReady(status, fragmentId));
    }

    private boolean l2GetReady(MemoryPipelineStatus status, String fragmentId) {
        try {
            return l2.get(fragmentId)
                    .filter(fragment -> status.getNamespace().equals(fragment.getNamespace()))
                    .isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean l2SearchReady(MemoryPipelineStatus status, String fragmentId) {
        try {
            Optional<MemoryFragment> archived = l3.retrieveFragment(fragmentId);
            if (archived.isEmpty()) {
                return false;
            }
            float[] queryEmbedding = archived.get().getL2Embedding() != null
                    ? archived.get().getL2Embedding()
                    : archived.get().getEmbedding();
            if (queryEmbedding == null) {
                return false;
            }
            int topK = Math.max(64, properties.getAsyncPipelineBenchmarkFragments() * 3
                    + properties.getAsyncPipelineBenchmarkWarmupFragments() * 2);
            return l2.search(queryEmbedding, status.getNamespace(), topK).stream()
                    .anyMatch(fragment -> fragmentId.equals(fragment.getId())
                            && status.getNamespace().equals(fragment.getNamespace()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean allL3Ready(MemoryPipelineStatus status) {
        List<String> fragmentIds = status == null || status.getFragmentIds() == null ? List.of() : status.getFragmentIds();
        return !fragmentIds.isEmpty() && fragmentIds.stream()
                .allMatch(fragmentId -> l3.retrieveFragment(fragmentId)
                        .filter(fragment -> status.getNamespace().equals(fragment.getNamespace()))
                        .isPresent());
    }

    private boolean hasStage(MemoryPipelineStatus status, MemoryPipelineStage stage) {
        return status != null
                && status.getCompletedStages() != null
                && status.getCompletedStages().contains(stage);
    }

    private List<String> stageNames(MemoryPipelineStatus status) {
        if (status == null || status.getCompletedStages() == null) {
            return List.of();
        }
        return status.getCompletedStages().stream()
                .map(MemoryPipelineStage::name)
                .toList();
    }

    private MemoryPipelineRequest buildRequest(String mode, String namespace, String runId, int index) {
        String modeSlug = mode.toLowerCase().replace('_', '-');
        String pipelineId = "async-pipeline-latency::%s::%s::%03d".formatted(runId, modeSlug, index + 1);
        String service = serviceName(index);
        String content = """
                User preference: main-path latency benchmark memory %d for %s in %s mode.
                Decision: keep request retrieval, rerank, and prompt assembly on the main path.
                Tool result: memory write policy for %s is measured after the return payload is ready.
                Follow-up: compare synchronous persistence with asynchronous pipeline admission under the same deterministic workload.
                """.formatted(index + 1, service, mode, mode);
        return MemoryPipelineRequest.builder()
                .pipelineId(pipelineId)
                .namespace(namespace)
                .content(content)
                .tags(List.of("main-path-latency-benchmark", modeSlug, service))
                .reasoningChainId("main-path-latency-" + runId)
                .build();
    }

    private RecallQuery buildRecallQuery(String namespace, int index) {
        String service = serviceName(index);
        return RecallQuery.builder()
                .namespace(namespace)
                .query("Which owner and deployment guardrail currently apply to " + service + "?")
                .topK(Math.max(3, properties.getRecallTopK()))
                .tokenBudget(Math.max(256, properties.getRecallTokenBudget()))
                .tags(List.of("main-path-latency-benchmark", service))
                .retrievalMode(RetrievalMode.HYBRID)
                .rerankEnabled(true)
                .rerankerType(RerankerType.LINEAR_SCORE_FUSION)
                .scenario(properties.getLearningScenario())
                .build();
    }

    private String buildQuestion(int index) {
        String service = serviceName(index);
        return "Which owner and deployment guardrail currently apply to " + service + "?";
    }

    private String buildTaskContext(String mode, int index) {
        return "benchmarkMode=" + mode + "\ncaseIndex=" + (index + 1) + "\nexpectedService=" + serviceName(index);
    }

    private boolean recallSucceeded(RecallResult recallResult, int index) {
        String service = serviceName(index);
        return recallResult != null
                && recallResult.getFragments() != null
                && recallResult.getFragments().stream()
                .map(RecallResult.ScoredFragment::getFragment)
                .anyMatch(fragment -> fragment != null
                        && fragment.getContent() != null
                        && fragment.getContent().contains(service)
                        && fragment.getContent().contains(service + "-owner"));
    }

    private int fragmentCount(RecallResult recallResult) {
        return recallResult == null || recallResult.getFragments() == null ? 0 : recallResult.getFragments().size();
    }

    private int recallCandidateCount(RecallResult recallResult) {
        RecallDiagnostics diagnostics = recallResult == null ? null : recallResult.getDiagnostics();
        if (diagnostics == null) {
            return 0;
        }
        return diagnostics.getL1CandidateCount()
                + diagnostics.getL2SearchCandidateCount()
                + diagnostics.getL2NamespaceFallbackCandidateCount()
                + diagnostics.getKeywordCandidateCount();
    }

    private int rerankCandidateCount(RecallResult recallResult) {
        RecallDiagnostics diagnostics = recallResult == null ? null : recallResult.getDiagnostics();
        return diagnostics == null ? 0 : diagnostics.getRerankCandidateCount();
    }

    private int l1CandidateCount(RecallResult recallResult) {
        RecallDiagnostics diagnostics = recallResult == null ? null : recallResult.getDiagnostics();
        return diagnostics == null ? 0 : diagnostics.getL1CandidateCount();
    }

    private int l2SearchCandidateCount(RecallResult recallResult) {
        RecallDiagnostics diagnostics = recallResult == null ? null : recallResult.getDiagnostics();
        return diagnostics == null ? 0 : diagnostics.getL2SearchCandidateCount();
    }

    private int keywordCandidateCount(RecallResult recallResult) {
        RecallDiagnostics diagnostics = recallResult == null ? null : recallResult.getDiagnostics();
        return diagnostics == null ? 0 : diagnostics.getKeywordCandidateCount();
    }

    private boolean fragmentsVisibleInL1(MemoryPipelineStatus status) {
        List<String> fragmentIds = status == null || status.getFragmentIds() == null
                ? List.of()
                : status.getFragmentIds();
        return !fragmentIds.isEmpty() && fragmentIds.stream().allMatch(id -> l1.peek(id).isPresent());
    }

    private String serviceName(int index) {
        return "service-" + (index + 1);
    }

    private float[] embeddingFor(int index) {
        int dimension = l2.vectorDimension() > 0 ? l2.vectorDimension() : 8;
        float[] embedding = new float[dimension];
        embedding[index % embedding.length] = 1.0f;
        embedding[(index + 3) % embedding.length] = 0.25f;
        return embedding;
    }

    private void cleanup(List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results) {
        for (AsyncPipelineLatencyBenchmarkReport.CaseResult result : results) {
            for (String fragmentId : result.getFragmentIds() == null ? List.<String>of() : result.getFragmentIds()) {
                try {
                    l1.remove(fragmentId);
                    l2.delete(fragmentId);
                    l3.deleteFragment(fragmentId);
                } catch (RuntimeException e) {
                    log.debug("Failed to clean async pipeline benchmark fragment id={}: {}",
                            fragmentId, e.getMessage());
                }
            }
        }
    }

    private double elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000.0d;
    }

    private double latencyBetween(Instant startedAt, Instant completedAt) {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            return 0.0d;
        }
        return java.time.Duration.between(startedAt, completedAt).toNanos() / 1_000_000.0d;
    }
    private AsyncPipelineLatencyBenchmarkReport.BackpressureSummary runBackpressureProbe(
            String namespace,
            String runId,
            List<AsyncPipelineLatencyBenchmarkReport.CaseResult> mainPathResults) {
        AsyncMemoryPipeline.PipelineQueueSnapshot before = memoryPipeline.queueSnapshot();
        int probeCount = Math.max(
                Math.max(1, properties.getAsyncPipelineBenchmarkAsyncParallelism()),
                before.maxWorkers() + Math.min(before.queueCapacity(), 16) + 2);
        List<ProbeSubmission> submissions = new ArrayList<>(probeCount);
        List<Double> submissionLatencies = new ArrayList<>(probeCount);
        long maxObservedQueueSize = mainPathResults == null ? 0L : mainPathResults.stream()
                .mapToLong(result -> Math.max(result.getQueueSizeBefore(), result.getQueueSizeAfter()))
                .max()
                .orElse(0L);
        long callerRunsBefore = before.callerRunsCount();
        int submitErrors = 0;

        for (int index = 0; index < probeCount; index++) {
            MemoryPipelineRequest request = buildBackpressureRequest(namespace, runId, index);
            long startedAt = System.nanoTime();
            try {
                MemoryPipelineStatus accepted = memoryPipeline.submit(request);
                double submissionLatencyMs = elapsedMillis(startedAt);
                submissionLatencies.add(submissionLatencyMs);
                submissions.add(new ProbeSubmission(accepted.getPipelineId(), startedAt));
                AsyncMemoryPipeline.PipelineQueueSnapshot snapshot = memoryPipeline.queueSnapshot();
                maxObservedQueueSize = Math.max(maxObservedQueueSize, snapshot.queueSize());
            } catch (RuntimeException e) {
                submitErrors++;
                log.warn("Backpressure probe submit failed index={} namespace={}", index, namespace, e);
            }
        }

        int completed = 0;
        int completionErrors = 0;
        List<Double> readinessLatencies = new ArrayList<>(submissions.size());
        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> cleanupResults = new ArrayList<>();
        for (ProbeSubmission submission : submissions) {
            try {
                MemoryPipelineStatus status = waitForCompletion(submission.pipelineId());
                waitForReadiness(status);
                readinessLatencies.add((System.nanoTime() - submission.startedAtNanos()) / 1_000_000.0d);
                completed++;
                cleanupResults.add(caseBuilder(completed, ASYNC_MODE, status).build());
            } catch (RuntimeException e) {
                completionErrors++;
                log.warn("Backpressure probe completion failed pipelineId={}", submission.pipelineId(), e);
            }
            AsyncMemoryPipeline.PipelineQueueSnapshot snapshot = memoryPipeline.queueSnapshot();
            maxObservedQueueSize = Math.max(maxObservedQueueSize, snapshot.queueSize());
        }
        cleanup(cleanupResults);

        AsyncMemoryPipeline.PipelineQueueSnapshot after = memoryPipeline.queueSnapshot();
        maxObservedQueueSize = Math.max(maxObservedQueueSize, after.queueSize());
        long mainPathCallerRunsDelta = mainPathResults == null ? 0L : mainPathResults.stream()
                .mapToLong(result -> Math.max(0L, result.getCallerRunsCountAfter() - result.getCallerRunsCountBefore()))
                .sum();
        long probeCallerRunsDelta = Math.max(0L, after.callerRunsCount() - callerRunsBefore);
        int errors = submitErrors + completionErrors;
        return AsyncPipelineLatencyBenchmarkReport.BackpressureSummary.builder()
                .policy(after.backpressurePolicy())
                .queueCapacity(after.queueCapacity())
                .queueSize(after.queueSize())
                .queueRemainingCapacity(after.queueRemainingCapacity())
                .activeWorkers(after.activeWorkers())
                .maxWorkers(after.maxWorkers())
                .callerRunsCount(after.callerRunsCount())
                .callerRunsCountDuringBenchmark(mainPathCallerRunsDelta + probeCallerRunsDelta)
                .maxObservedQueueSize(maxObservedQueueSize)
                .saturated(maxObservedQueueSize >= after.queueCapacity() && after.queueCapacity() > 0)
                .probeSubmittedCount(submissions.size())
                .probeCompletedCount(completed)
                .probeErrorCount(errors)
                .probeSubmissionLatencyP50Ms(percentile(submissionLatencies, 0.50d))
                .probeSubmissionLatencyP95Ms(percentile(submissionLatencies, 0.95d))
                .probeSubmissionLatencyP99Ms(percentile(submissionLatencies, 0.99d))
                .probeSubmissionLatencyAverageMs(average(submissionLatencies))
                .probeReadinessLatencyP50Ms(percentile(readinessLatencies, 0.50d))
                .probeReadinessLatencyP95Ms(percentile(readinessLatencies, 0.95d))
                .probeReadinessLatencyP99Ms(percentile(readinessLatencies, 0.99d))
                .probeReadinessLatencyAverageMs(average(readinessLatencies))
                .build();
    }

    private MemoryPipelineRequest buildBackpressureRequest(String namespace, String runId, int index) {
        String pipelineId = "async-pipeline-latency::%s::backpressure-probe::%03d".formatted(runId, index + 1);
        String service = serviceName(index);
        String content = """
                User preference: backpressure probe memory %d for %s.
                Decision: keep the asynchronous memory pipeline bounded under burst submission.
                Tool result: the benchmark records queue size, CallerRuns activation, submission latency, and readiness latency.
                Follow-up: this probe is excluded from main-path latency percentile calculations.
                """.formatted(index + 1, service);
        return MemoryPipelineRequest.builder()
                .pipelineId(pipelineId)
                .namespace(namespace)
                .content(content)
                .tags(List.of("main-path-latency-benchmark", "backpressure-probe", service))
                .reasoningChainId("main-path-latency-backpressure-" + runId)
                .build();
    }

    private record ProbeSubmission(String pipelineId, long startedAtNanos) {
    }
    static Map<String, AsyncPipelineLatencyBenchmarkReport.ModeSummary> buildModeSummaries(
            List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results) {
        if (results == null || results.isEmpty()) {
            return Map.of();
        }
        Map<String, AsyncPipelineLatencyBenchmarkReport.ModeSummary> summaries = results.stream()
                .collect(Collectors.groupingBy(
                        AsyncPipelineLatencyBenchmarkReport.CaseResult::getMode,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), AsyncPipelineLatencyBenchmarkRunner::summarizeMode)));
        Map<String, AsyncPipelineLatencyBenchmarkReport.ModeSummary> ordered = new LinkedHashMap<>();
        if (summaries.containsKey(SYNC_MODE)) {
            ordered.put(SYNC_MODE, summaries.get(SYNC_MODE));
        }
        if (summaries.containsKey(ASYNC_MODE)) {
            ordered.put(ASYNC_MODE, summaries.get(ASYNC_MODE));
        }
        summaries.entrySet().stream()
                .filter(entry -> !ordered.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    static AsyncPipelineLatencyBenchmarkReport.ModeSummary summarizeMode(
            List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results) {
        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> safeResults =
                results == null ? List.of() : List.copyOf(results);
        int total = safeResults.size();
        int errors = (int) safeResults.stream().filter(result -> !isBlank(result.getErrorMessage())).count();
        int persistenceSuccesses = (int) safeResults.stream()
                .filter(AsyncPipelineLatencyBenchmarkReport.CaseResult::isPersistenceSucceeded)
                .count();
        int mainPathSuccesses = (int) safeResults.stream()
                .filter(AsyncPipelineLatencyBenchmarkReport.CaseResult::isMainPathSucceeded)
                .count();
        int writeThroughVisibleCount = (int) safeResults.stream()
                .filter(AsyncPipelineLatencyBenchmarkReport.CaseResult::isWriteThroughVisibleAtReturn)
                .count();
        int recallSuccesses = (int) safeResults.stream()
                .filter(AsyncPipelineLatencyBenchmarkReport.CaseResult::isRecallSucceeded)
                .count();
        int promptAssemblySuccesses = (int) safeResults.stream()
                .filter(AsyncPipelineLatencyBenchmarkReport.CaseResult::isPromptAssemblySucceeded)
                .count();
        int l2ReadyCount = (int) safeResults.stream()
                .filter(AsyncPipelineLatencyBenchmarkReport.CaseResult::isL2Ready)
                .count();
        int l3ReadyCount = (int) safeResults.stream()
                .filter(AsyncPipelineLatencyBenchmarkReport.CaseResult::isL3Ready)
                .count();
        List<Double> mainLatencies = safeResults.stream()
                .map(AsyncPipelineLatencyBenchmarkReport.CaseResult::getMainPathLatencyMs)
                .toList();
        List<Double> recallLatencies = safeResults.stream()
                .map(AsyncPipelineLatencyBenchmarkReport.CaseResult::getRecallLatencyMs)
                .toList();
        List<Double> promptLatencies = safeResults.stream()
                .map(AsyncPipelineLatencyBenchmarkReport.CaseResult::getPromptAssemblyLatencyMs)
                .toList();
        List<Double> submissionLatencies = safeResults.stream()
                .map(AsyncPipelineLatencyBenchmarkReport.CaseResult::getMemoryWriteSubmissionLatencyMs)
                .toList();
        List<Double> asyncPipelineLatencies = safeResults.stream()
                .map(AsyncPipelineLatencyBenchmarkReport.CaseResult::getAsyncPipelineLatencyMs)
                .toList();
        List<Double> readinessLatencies = safeResults.stream()
                .map(AsyncPipelineLatencyBenchmarkReport.CaseResult::getReadinessLatencyMs)
                .toList();
        double mainAverage = average(mainLatencies);
        double readinessAverage = average(readinessLatencies);
        double asyncPipelineAverage = average(asyncPipelineLatencies);
        return AsyncPipelineLatencyBenchmarkReport.ModeSummary.builder()
                .total(total)
                .successes(persistenceSuccesses)
                .mainPathSuccesses(mainPathSuccesses)
                .writeThroughVisibleCount(writeThroughVisibleCount)
                .recallSuccesses(recallSuccesses)
                .promptAssemblySuccesses(promptAssemblySuccesses)
                .errors(errors)
                .l2ReadyCount(l2ReadyCount)
                .l3ReadyCount(l3ReadyCount)
                .extractionCompletedCount(stageCount(safeResults, MemoryPipelineStage.EXTRACTION))
                .summaryCompletedCount(stageCount(safeResults, MemoryPipelineStage.SUMMARY))
                .embeddingCompletedCount(stageCount(safeResults, MemoryPipelineStage.EMBEDDING))
                .l1AdmissionCompletedCount(stageCount(safeResults, MemoryPipelineStage.L1_ADMISSION))
                .l2IndexCompletedCount(stageCount(safeResults, MemoryPipelineStage.L2_INDEX))
                .l3ArchiveCompletedCount(stageCount(safeResults, MemoryPipelineStage.L3_ARCHIVE))
                .mainPathSuccessRate(total == 0 ? 0.0d : (double) mainPathSuccesses / total)
                .writeThroughVisibilityRate(total == 0 ? 0.0d : (double) writeThroughVisibleCount / total)
                .persistenceSuccessRate(total == 0 ? 0.0d : (double) persistenceSuccesses / total)
                .mainPathLatencyP50Ms(percentile(mainLatencies, 0.50d))
                .mainPathLatencyP95Ms(percentile(mainLatencies, 0.95d))
                .mainPathLatencyP99Ms(percentile(mainLatencies, 0.99d))
                .mainPathLatencyAverageMs(mainAverage)
                .mainPathLatencyMinMs(min(mainLatencies))
                .mainPathLatencyMaxMs(max(mainLatencies))
                .recallLatencyP50Ms(percentile(recallLatencies, 0.50d))
                .recallLatencyP95Ms(percentile(recallLatencies, 0.95d))
                .recallLatencyP99Ms(percentile(recallLatencies, 0.99d))
                .recallLatencyAverageMs(average(recallLatencies))
                .promptAssemblyLatencyP50Ms(percentile(promptLatencies, 0.50d))
                .promptAssemblyLatencyP95Ms(percentile(promptLatencies, 0.95d))
                .promptAssemblyLatencyP99Ms(percentile(promptLatencies, 0.99d))
                .promptAssemblyLatencyAverageMs(average(promptLatencies))
                .memoryWriteSubmissionLatencyP50Ms(percentile(submissionLatencies, 0.50d))
                .memoryWriteSubmissionLatencyP95Ms(percentile(submissionLatencies, 0.95d))
                .memoryWriteSubmissionLatencyP99Ms(percentile(submissionLatencies, 0.99d))
                .memoryWriteSubmissionLatencyAverageMs(average(submissionLatencies))
                .asyncPipelineLatencyP50Ms(percentile(asyncPipelineLatencies, 0.50d))
                .asyncPipelineLatencyP95Ms(percentile(asyncPipelineLatencies, 0.95d))
                .asyncPipelineLatencyP99Ms(percentile(asyncPipelineLatencies, 0.99d))
                .asyncPipelineLatencyAverageMs(asyncPipelineAverage)
                .asyncPipelineThroughputPerSecond(asyncPipelineAverage <= 0.0d ? 0.0d : 1000.0d / asyncPipelineAverage)
                .readinessLatencyP50Ms(percentile(readinessLatencies, 0.50d))
                .readinessLatencyP95Ms(percentile(readinessLatencies, 0.95d))
                .readinessLatencyP99Ms(percentile(readinessLatencies, 0.99d))
                .readinessLatencyAverageMs(readinessAverage)
                .readinessLatencyMinMs(min(readinessLatencies))
                .readinessLatencyMaxMs(max(readinessLatencies))
                .readinessLagAverageMs(Math.max(0.0d, readinessAverage - mainAverage))
                .returnedFragmentAverage(average(safeResults.stream()
                        .map(result -> (double) result.getReturnedFragmentCount())
                        .toList()))
                .rerankCandidateAverage(average(safeResults.stream()
                        .map(result -> (double) result.getRerankCandidateCount())
                        .toList()))
                .build();
    }

    private static int stageCount(
            List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results,
            MemoryPipelineStage stage) {
        String stageName = stage.name();
        return (int) results.stream()
                .filter(result -> result.getCompletedStages() != null && result.getCompletedStages().contains(stageName))
                .count();
    }

    static AsyncPipelineLatencyBenchmarkReport.BackpressureSummary buildBackpressureSummary(
            AsyncMemoryPipeline.PipelineQueueSnapshot queueSnapshot,
            List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results) {
        List<AsyncPipelineLatencyBenchmarkReport.CaseResult> safeResults =
                results == null ? List.of() : List.copyOf(results);
        long maxQueueSize = safeResults.stream()
                .mapToLong(result -> Math.max(result.getQueueSizeBefore(), result.getQueueSizeAfter()))
                .max()
                .orElse(queueSnapshot == null ? 0 : queueSnapshot.queueSize());
        long callerRunsDelta = safeResults.stream()
                .mapToLong(result -> Math.max(0L, result.getCallerRunsCountAfter() - result.getCallerRunsCountBefore()))
                .sum();
        int queueCapacity = queueSnapshot == null ? 0 : queueSnapshot.queueCapacity();
        String policy = queueSnapshot == null ? "UNKNOWN" : queueSnapshot.backpressurePolicy();
        return AsyncPipelineLatencyBenchmarkReport.BackpressureSummary.builder()
                .policy(policy)
                .queueCapacity(queueCapacity)
                .queueSize(queueSnapshot == null ? 0 : queueSnapshot.queueSize())
                .queueRemainingCapacity(queueSnapshot == null ? 0 : queueSnapshot.queueRemainingCapacity())
                .activeWorkers(queueSnapshot == null ? 0 : queueSnapshot.activeWorkers())
                .maxWorkers(queueSnapshot == null ? 0 : queueSnapshot.maxWorkers())
                .callerRunsCount(queueSnapshot == null ? 0L : queueSnapshot.callerRunsCount())
                .callerRunsCountDuringBenchmark(callerRunsDelta)
                .maxObservedQueueSize(maxQueueSize)
                .saturated(maxQueueSize >= queueCapacity && queueCapacity > 0)
                .build();
    }

    static double relativeReduction(double baseline, double candidate) {
        return baseline == 0.0d ? 0.0d : (baseline - candidate) / baseline;
    }

    static double percentile(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        List<Double> sorted = values.stream()
                .filter(value -> value != null && !value.isNaN() && !value.isInfinite())
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    private static double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        return values.stream()
                .filter(value -> value != null && !value.isNaN() && !value.isInfinite())
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0d);
    }

    private static double min(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        return values.stream()
                .filter(value -> value != null && !value.isNaN() && !value.isInfinite())
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0.0d);
    }

    private static double max(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        return values.stream()
                .filter(value -> value != null && !value.isNaN() && !value.isInfinite())
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0d);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
