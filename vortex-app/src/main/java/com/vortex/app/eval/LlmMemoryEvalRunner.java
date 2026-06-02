package com.vortex.app.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.GenerationLatencyBreakdown;
import com.vortex.common.dto.GenerationRequest;
import com.vortex.common.dto.GenerationResult;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.RecallDiagnostics;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.exception.GenerationException;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.hmc.AdaptiveWeightLearner;
import com.vortex.kernel.generation.GenerationService;
import com.vortex.kernel.generation.PromptAssembler;
import com.vortex.kernel.generation.PromptAssemblyRequest;
import com.vortex.kernel.generation.PromptAssemblyResult;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.storage.api.L2WarmStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmMemoryEvalRunner {

    private static final TypeReference<List<LlmMemoryEvalCase>> CASE_SET_TYPE = new TypeReference<>() {};
    private static final String EVAL_MEMORY_TAG = "llm-memory-eval-memory";
    private static final String EVAL_EVICTION_FILLER_TAG = "llm-memory-eval-eviction-filler";
    static final String FAILURE_RECALL_MISS = "recall_miss";
    static final String FAILURE_INSUFFICIENT_WHEN_MEMORY_AVAILABLE = "insufficient_when_memory_available";
    static final String FAILURE_RUNTIME_ERROR = "runtime_error";
    static final String RUNTIME_ERROR_RUNNER_INTERNAL = "runner_internal_error";

    private final HierarchicalMemoryController hmc;
    private final PromptAssembler promptAssembler;
    private final RuleBasedAnswerJudge answerJudge;
    private final ObjectProvider<GenerationService> generationServiceProvider;
    private final L2WarmStore l2WarmStore;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final LlmMemoryEvalProperties properties;

    @Qualifier("bgeSmallEmbeddingService")
    private final TokenCounter tokenCounter;

    public List<LlmMemoryEvalCase> loadDefaultCaseSet() {
        return loadCaseSet(properties.getDatasetLocation());
    }

    public List<LlmMemoryEvalCase> loadCaseSet(String datasetLocation) {
        if (isBlank(datasetLocation)) {
            throw new IllegalArgumentException("Eval dataset location must not be blank");
        }
        Resource resource = resourceLoader.getResource(datasetLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("Eval dataset not found: " + datasetLocation);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            List<LlmMemoryEvalCase> cases = objectMapper.readValue(inputStream, CASE_SET_TYPE);
            log.info("Loaded LLM memory eval dataset location={} cases={}",
                    datasetLocation, cases.size());
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load eval dataset from " + datasetLocation, e);
        }
    }

    public LlmMemoryEvalReport runDefaultBaselines() {
        return run(loadDefaultCaseSet(), EnumSet.of(
                LlmMemoryEvalMode.BASELINE_NO_MEMORY,
                LlmMemoryEvalMode.VORTEX_MEMORY));
    }

    public LlmMemoryEvalReport runConfiguredModes() {
        return run(loadDefaultCaseSet(), properties.getModes());
    }

    public LlmMemoryEvalReport run(List<LlmMemoryEvalCase> cases, Collection<LlmMemoryEvalMode> modes) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Eval runner requires at least one case");
        }
        if (modes == null || modes.isEmpty()) {
            throw new IllegalArgumentException("Eval runner requires at least one mode");
        }

        GenerationService generationService = generationServiceProvider.getIfAvailable();
        if (generationService == null) {
            throw new IllegalStateException(
                    "No GenerationService is available. Enable vortex.kernel.generation.enabled or provide a GenerationService bean.");
        }

        String runId = UUID.randomUUID().toString().substring(0, 8);
        List<LlmMemoryEvalResult> results = new ArrayList<>();
        for (LlmMemoryEvalCase evalCase : cases) {
            for (LlmMemoryEvalMode mode : modes) {
                results.add(runSingleCase(generationService, evalCase, mode, runId));
            }
        }
        return LlmMemoryEvalReport.builder()
                .generatedAt(Instant.now())
                .totalCases(cases.size())
                .totalRuns(results.size())
                .results(List.copyOf(results))
                .modeSummaries(buildModeSummaries(results))
                .build();
    }

    private LlmMemoryEvalResult runSingleCase(
            GenerationService generationService,
            LlmMemoryEvalCase evalCase,
            LlmMemoryEvalMode mode,
            String runId) {
        validateCase(evalCase);
        long startedAt = System.nanoTime();
        String namespace = scopedNamespace(evalCase, mode, runId);
        List<String> storedFragmentIds = new ArrayList<>();
        Map<String, String> actualToLogicalIds = new LinkedHashMap<>();
        RecallResult recallResult = null;
        List<String> expectedFragments = safeList(evalCase.getExpectedFragments());
        Boolean evictedBeforeAnswer = null;
        EvalLatencyBreakdown latencyBreakdown = new EvalLatencyBreakdown();

        try {
            if (mode.usesMemory()) {
                long storeStartedAt = System.nanoTime();
                storeCaseFragments(evalCase, mode, namespace, runId, storedFragmentIds, actualToLogicalIds);
                latencyBreakdown.storeLatencyMs = elapsedMillis(storeStartedAt);
                List<String> recoveryTargetIds = resolveRecoveryTargetIds(expectedFragments, storedFragmentIds, actualToLogicalIds);
                WaitOutcome targetWait = waitForRecoveryTargets(recoveryTargetIds, namespace);
                latencyBreakdown.recoveryTargetWaitLatencyMs = targetWait.elapsedMs();
                latencyBreakdown.recoveryTargetWaitPollCount = targetWait.pollCount();
                if (mode.requiresEvictionRecovery()) {
                    RecoveryPreparationOutcome recoveryOutcome =
                            forceRecoveryPath(evalCase, mode, namespace, runId, recoveryTargetIds, storedFragmentIds);
                    evictedBeforeAnswer = recoveryOutcome.evictedBeforeAnswer();
                    latencyBreakdown.recoveryForceLatencyMs = recoveryOutcome.elapsedMs();
                    latencyBreakdown.recoveryForcePollCount = recoveryOutcome.pollCount();
                    latencyBreakdown.recoveryFillerFragmentsInserted = recoveryOutcome.fillerFragmentsInserted();
                    if (!recoveryOutcome.evictedBeforeAnswer()) {
                        throw new IllegalStateException(
                                "Failed to force recovery eviction before answer caseId=%s namespace=%s targets=%s fillersInserted=%s"
                                        .formatted(
                                                evalCase.getCaseId(),
                                                namespace,
                                                recoveryTargetIds,
                                                recoveryOutcome.fillerFragmentsInserted()));
                    }
                }
                long recallStartedAt = System.nanoTime();
                recallResult = hmc.recall(RecallQuery.builder()
                        .query(evalCase.getQuestion())
                        .namespace(namespace)
                        .topK(Math.max(properties.getRecallTopK(), expectedFragments.size()))
                        .tokenBudget(properties.getRecallTokenBudget())
                        .scenario(properties.getLearningScenario())
                        .tags(recallTags(evalCase))
                        .build());
                latencyBreakdown.recallLatencyMs = elapsedMillis(recallStartedAt);
            }

            long promptAssemblyStartedAt = System.nanoTime();
            PromptAssemblyResult prompt = promptAssembler.assemble(new PromptAssemblyRequest(
                    properties.getSystemPrompt(),
                    evalCase.getQuestion(),
                    recallResult,
                    null,
                    properties.getMaxPromptTokens()));
            latencyBreakdown.promptAssemblyLatencyMs = elapsedMillis(promptAssemblyStartedAt);

            long generationStartedAt = System.nanoTime();
            GenerationResult generationResult = generationService.generate(GenerationRequest.builder()
                    .systemPrompt(prompt.systemPrompt())
                    .userPrompt(prompt.userPrompt())
                    .metadata(Map.of(
                            "caseId", evalCase.getCaseId(),
                            "mode", mode.reportName(),
                            "namespace", namespace))
                    .build());
            latencyBreakdown.generationLatencyMs = generationResult.getLatencyMs() != null
                    ? generationResult.getLatencyMs()
                    : elapsedMillis(generationStartedAt);
            applyGenerationLatencyBreakdown(latencyBreakdown, generationResult.getLatencyBreakdown());

            List<String> returnedFragmentIds = mapReturnedFragmentIds(recallResult, actualToLogicalIds);
            List<String> recalledFromTiers = mapReturnedTiers(recallResult);
            boolean recallHit = !returnedFragmentIds.isEmpty()
                    && returnedFragmentIds.stream().anyMatch(expectedFragments::contains);
            RuleBasedAnswerJudge.Judgment judgment = answerJudge.evaluate(
                    evalCase.getExpectedAnswer(),
                    evalCase.getMustContain(),
                    evalCase.getMustNotContain(),
                    generationResult.getContent());
            String failureReason = classifyFailureReason(mode, expectedFragments, returnedFragmentIds, recallHit, judgment);
            boolean correct = judgment.correct() && isBlank(failureReason);
            long feedbackStartedAt = System.nanoTime();
            FeedbackObservation feedbackObservation =
                    maybeSubmitFeedback(mode, recallResult, expectedFragments, returnedFragmentIds, correct);
            latencyBreakdown.feedbackLatencyMs = elapsedMillis(feedbackStartedAt);
            logCaseCompletion(evalCase, mode, namespace, recallResult, evictedBeforeAnswer, latencyBreakdown);

            LlmMemoryEvalResult.LlmMemoryEvalResultBuilder builder = LlmMemoryEvalResult.builder()
                    .caseId(evalCase.getCaseId())
                    .mode(mode.reportName())
                    .question(evalCase.getQuestion())
                    .recallSessionId(recallResult == null ? null : recallResult.getRecallSessionId())
                    .returnedFragmentIds(returnedFragmentIds)
                    .recalledFromTiers(recalledFromTiers)
                    .generatedAnswer(generationResult.getContent())
                    .correct(correct)
                    .failureReason(failureReason)
                    .missingMustContain(judgment.missingMustContain())
                    .matchedForbiddenTerms(judgment.matchedForbiddenTerms())
                    .latencyMs(elapsedMillis(startedAt))
                    .promptTokens(generationResult.getPromptTokens() != null
                            ? generationResult.getPromptTokens()
                            : prompt.promptTokens())
                    .completionTokens(generationResult.getCompletionTokens())
                    .recallHit(recallHit)
                    .recallDiagnostics(extractRecallDiagnostics(recallResult))
                    .evictedBeforeAnswer(evictedBeforeAnswer)
                    .feedbackSubmitted(feedbackObservation.submitted())
                    .feedbackUsedFragmentIds(feedbackObservation.usedFragmentIds())
                    .learningSampleCountBefore(feedbackObservation.learningSampleCountBefore())
                    .learningSampleCountAfter(feedbackObservation.learningSampleCountAfter())
                    .learningActiveUpdateCountBefore(feedbackObservation.learningActiveUpdateCountBefore())
                    .learningActiveUpdateCountAfter(feedbackObservation.learningActiveUpdateCountAfter())
                    .learningShadowLiftBefore(feedbackObservation.learningShadowLiftBefore())
                    .learningShadowLiftAfter(feedbackObservation.learningShadowLiftAfter())
                    .learningBaselineLiftBefore(feedbackObservation.learningBaselineLiftBefore())
                    .learningBaselineLiftAfter(feedbackObservation.learningBaselineLiftAfter());
            applyLatencyBreakdown(builder, latencyBreakdown);
            return builder.build();
        } catch (RuntimeException e) {
            List<String> returnedFragmentIds = mapReturnedFragmentIds(recallResult, actualToLogicalIds);
            List<String> recalledFromTiers = mapReturnedTiers(recallResult);
            log.error("LLM eval case failed caseId={} mode={} namespace={} recallSessionId={}",
                    evalCase.getCaseId(), mode.reportName(), namespace,
                    recallResult == null ? null : recallResult.getRecallSessionId(), e);
            LlmMemoryEvalResult.LlmMemoryEvalResultBuilder builder = LlmMemoryEvalResult.builder()
                    .caseId(evalCase.getCaseId())
                    .mode(mode.reportName())
                    .question(evalCase.getQuestion())
                    .recallSessionId(recallResult == null ? null : recallResult.getRecallSessionId())
                    .returnedFragmentIds(returnedFragmentIds)
                    .recalledFromTiers(recalledFromTiers)
                    .generatedAnswer("")
                    .correct(false)
                    .failureReason(FAILURE_RUNTIME_ERROR)
                    .runtimeErrorType(runtimeErrorType(e))
                    .transientRuntimeError(transientRuntimeError(e))
                    .latencyMs(elapsedMillis(startedAt))
                    .promptTokens(null)
                    .completionTokens(null)
                    .recallHit(false)
                    .recallDiagnostics(extractRecallDiagnostics(recallResult))
                    .evictedBeforeAnswer(evictedBeforeAnswer)
                    .errorMessage(e.getMessage());
            applyLatencyBreakdown(builder, latencyBreakdown);
            applyGenerationExceptionBreakdown(builder, latencyBreakdown, e);
            return builder.build();
        } finally {
            cleanupStoredFragments(storedFragmentIds);
        }
    }

    private void storeCaseFragments(
            LlmMemoryEvalCase evalCase,
            LlmMemoryEvalMode mode,
            String namespace,
            String runId,
            List<String> storedFragmentIds,
            Map<String, String> actualToLogicalIds) {
        for (LlmMemoryEvalCase.EvalMemoryFragment fragment : evalCase.getMemoryFragments()) {
            if (fragment == null || isBlank(fragment.getFragmentId()) || isBlank(fragment.getContent())) {
                continue;
            }
            String actualFragmentId = scopedFragmentId(evalCase.getCaseId(), mode, fragment.getFragmentId(), runId);
            MemoryFragment storedFragment = MemoryFragment.builder()
                    .id(actualFragmentId)
                    .namespace(namespace)
                    .content(fragment.getContent())
                    .tokenCount(Math.max(1, tokenCounter.countTokens(fragment.getContent())))
                    .tags(evalMemoryTags(fragment))
                    .reasoningChainId(fragment.getReasoningChainId())
                    .build();
            if (fragment.getPinTtlMillis() != null) {
                storedFragment.pinForMillis(fragment.getPinTtlMillis());
            }
            hmc.storeFragment(storedFragment);
            storedFragmentIds.add(actualFragmentId);
            actualToLogicalIds.put(actualFragmentId, fragment.getFragmentId());
        }
        if (storedFragmentIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Eval case '%s' did not yield any storable memory fragments".formatted(evalCase.getCaseId()));
        }
    }

    private void cleanupStoredFragments(List<String> storedFragmentIds) {
        for (String fragmentId : storedFragmentIds) {
            try {
                hmc.deleteFragment(fragmentId);
            } catch (RuntimeException e) {
                log.warn("Failed to clean eval fragment fragmentId={}: {}", fragmentId, e.getMessage());
            }
        }
    }

    private List<String> mapReturnedFragmentIds(RecallResult recallResult, Map<String, String> actualToLogicalIds) {
        if (recallResult == null || recallResult.getFragments() == null) {
            return List.of();
        }
        return recallResult.getFragments().stream()
                .map(RecallResult.ScoredFragment::getFragment)
                .map(MemoryFragment::getId)
                .map(fragmentId -> actualToLogicalIds.getOrDefault(fragmentId, fragmentId))
                .toList();
    }

    private List<String> mapReturnedTiers(RecallResult recallResult) {
        if (recallResult == null || recallResult.getFragments() == null) {
            return List.of();
        }
        return recallResult.getFragments().stream()
                .map(RecallResult.ScoredFragment::getTier)
                .toList();
    }

    private RecallDiagnostics extractRecallDiagnostics(RecallResult recallResult) {
        return recallResult == null ? null : recallResult.getDiagnostics();
    }

    private String classifyFailureReason(
            LlmMemoryEvalMode mode,
            List<String> expectedFragments,
            List<String> returnedFragmentIds,
            boolean recallHit,
            RuleBasedAnswerJudge.Judgment judgment) {
        if (isRecallMiss(mode, expectedFragments, returnedFragmentIds)) {
            return FAILURE_RECALL_MISS;
        }
        if (judgment.correct()) {
            return null;
        }
        if (RuleBasedAnswerJudge.FAILURE_INSUFFICIENT_ANSWER.equals(judgment.failureReason())
                && mode.usesMemory()
                && recallHit) {
            return FAILURE_INSUFFICIENT_WHEN_MEMORY_AVAILABLE;
        }
        return judgment.failureReason();
    }

    private boolean isRecallMiss(
            LlmMemoryEvalMode mode,
            List<String> expectedFragments,
            List<String> returnedFragmentIds) {
        return mode.usesMemory()
                && !safeList(expectedFragments).isEmpty()
                && !safeList(returnedFragmentIds).containsAll(expectedFragments);
    }

    private Map<String, LlmMemoryEvalReport.ModeSummary> buildModeSummaries(List<LlmMemoryEvalResult> results) {
        return results.stream()
                .collect(Collectors.groupingBy(
                        LlmMemoryEvalResult::getMode,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), grouped -> {
                            int total = grouped.size();
                            long correct = grouped.stream().filter(LlmMemoryEvalResult::isCorrect).count();
                            long recallHits = grouped.stream().filter(LlmMemoryEvalResult::isRecallHit).count();
                            long recoveredRuns = grouped.stream()
                                    .filter(result -> Boolean.TRUE.equals(result.getEvictedBeforeAnswer()))
                                    .count();
                            long recoveredCorrect = grouped.stream()
                                    .filter(result -> Boolean.TRUE.equals(result.getEvictedBeforeAnswer()) && result.isCorrect())
                                    .count();
                            long recoveredL2Hits = grouped.stream()
                                    .filter(result -> Boolean.TRUE.equals(result.getEvictedBeforeAnswer()))
                                    .filter(result -> safeList(result.getRecalledFromTiers()).contains("L2"))
                                    .count();
                            long feedbackSubmitted = grouped.stream()
                                    .filter(result -> Boolean.TRUE.equals(result.getFeedbackSubmitted()))
                                    .count();
                            long learningSampleDelta = grouped.stream()
                                    .mapToLong(result -> delta(result.getLearningSampleCountBefore(), result.getLearningSampleCountAfter()))
                                    .sum();
                            long learningUpdateDelta = grouped.stream()
                                    .mapToLong(result -> delta(result.getLearningActiveUpdateCountBefore(), result.getLearningActiveUpdateCountAfter()))
                                    .sum();
                            double averageLatency = grouped.stream()
                                    .mapToLong(LlmMemoryEvalResult::getLatencyMs)
                                    .average()
                                    .orElse(0.0d);
                            return LlmMemoryEvalReport.ModeSummary.builder()
                                    .total(total)
                                    .correct((int) correct)
                                    .accuracy(total == 0 ? 0.0d : (double) correct / total)
                                    .recallHitRate(total == 0 ? 0.0d : (double) recallHits / total)
                                    .averageLatencyMs(averageLatency)
                                    .recoveredRuns((int) recoveredRuns)
                                    .recoveredAccuracy(recoveredRuns == 0 ? 0.0d : (double) recoveredCorrect / recoveredRuns)
                                    .recoveredL2HitRate(recoveredRuns == 0 ? 0.0d : (double) recoveredL2Hits / recoveredRuns)
                                    .feedbackSubmitted((int) feedbackSubmitted)
                                    .learningSampleCountDelta(learningSampleDelta)
                                    .learningUpdateCountDelta(learningUpdateDelta)
                                    .build();
                        })));
    }

    private FeedbackObservation maybeSubmitFeedback(
            LlmMemoryEvalMode mode,
            RecallResult recallResult,
            List<String> expectedFragments,
            List<String> returnedFragmentIds,
            boolean correct) {
        if (!properties.isFeedbackEnabled()
                || !mode.usesMemory()
                || recallResult == null
                || isBlank(recallResult.getRecallSessionId())) {
            return FeedbackObservation.notSubmitted();
        }

        AdaptiveWeightLearner.LearningSnapshot before = hmc.learningSnapshot(properties.getLearningScenario());
        Set<String> expectedFragmentSet = new HashSet<>(expectedFragments);
        List<String> usedFragmentIds = returnedFragmentIds.stream()
                .filter(expectedFragmentSet::contains)
                .distinct()
                .toList();
        boolean answerAccepted = correct && !usedFragmentIds.isEmpty();

        hmc.recordFeedback(MemoryFeedbackRequest.builder()
                .recallSessionId(recallResult.getRecallSessionId())
                .usedFragmentIds(usedFragmentIds)
                .answerAccepted(answerAccepted)
                .build());

        AdaptiveWeightLearner.LearningSnapshot after = hmc.learningSnapshot(properties.getLearningScenario());
        return FeedbackObservation.submitted(usedFragmentIds, before, after);
    }

    private List<String> resolveRecoveryTargetIds(
            List<String> expectedFragments,
            List<String> storedFragmentIds,
            Map<String, String> actualToLogicalIds) {
        if (expectedFragments == null || expectedFragments.isEmpty()) {
            return List.copyOf(storedFragmentIds);
        }
        Set<String> expectedFragmentSet = new HashSet<>(expectedFragments);
        return actualToLogicalIds.entrySet().stream()
                .filter(entry -> expectedFragmentSet.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private WaitOutcome waitForRecoveryTargets(List<String> targetIds, String namespace) {
        if (targetIds.isEmpty()) {
            return new WaitOutcome(true, 0L, 0);
        }
        return waitForCondition(
                () -> targetIds.stream().allMatch(fragmentId -> l2WarmStore.get(fragmentId)
                        .filter(fragment -> namespace.equals(fragment.getNamespace()))
                        .isPresent()),
                () -> "Timed out waiting for recovery targets to persist to L2 namespace=%s targets=%s"
                        .formatted(namespace, targetIds));
    }

    private RecoveryPreparationOutcome forceRecoveryPath(
            LlmMemoryEvalCase evalCase,
            LlmMemoryEvalMode mode,
            String namespace,
            String runId,
            List<String> recoveryTargetIds,
            List<String> storedFragmentIds) {
        long startedAt = System.nanoTime();
        long recoveryDeadline = System.currentTimeMillis()
                + Math.max(0L, properties.getRecoveryPollTimeout().toMillis());
        if (recoveryTargetIds.isEmpty()) {
            return new RecoveryPreparationOutcome(false, 0L, 0, 0);
        }
        if (isAnyRecoveryTargetEvicted(recoveryTargetIds)) {
            return new RecoveryPreparationOutcome(true, 0L, 0, 0);
        }

        int fillerFragmentsInserted = 0;
        int pollCount = 0;
        long settleWindowMillis = Math.max(1L, properties.getRecoveryPollInterval().toMillis());
        RecoveryFillerPlan basePlan = planRecoveryFillers(namespace, recoveryTargetIds);
        RecoveryFillerPlan fillerPlan = basePlan;
        int batchSize = Math.max(1, fillerPlan.maxFillers());
        int completedBatches = 0;
        for (int i = 0; System.currentTimeMillis() < recoveryDeadline; i++) {
            if (i > 0 && i % batchSize == 0) {
                completedBatches++;
                int pressureRound = completedBatches;
                fillerPlan = escalateRecoveryFillerPlan(namespace, basePlan, pressureRound);
                batchSize = Math.max(1, fillerPlan.maxFillers());
            }
            String fillerId = scopedFragmentId(
                    evalCase.getCaseId(),
                    mode,
                    "eviction-filler-" + i,
                    runId);
            MemoryFragment filler = MemoryFragment.builder()
                    .id(fillerId)
                    .namespace(namespace)
                    .content(buildEvictionFillerContent(evalCase, i, fillerPlan.fillerTokens()))
                    .tokenCount(fillerPlan.fillerTokens())
                    .importance(properties.getEvictionFillerImportance())
                    .tags(List.of(EVAL_EVICTION_FILLER_TAG))
                    .reasoningChainId("llm-memory-eval-eviction-" + i)
                    .build();
            hmc.storeFragment(filler);
            storedFragmentIds.add(fillerId);
            fillerFragmentsInserted++;

            WaitOutcome evictionWait = waitForConditionUntil(
                    () -> isAnyRecoveryTargetEvicted(recoveryTargetIds),
                    null,
                    Math.min(recoveryDeadline, System.currentTimeMillis() + settleWindowMillis));
            pollCount += evictionWait.pollCount();
            if (evictionWait.satisfied()) {
                return new RecoveryPreparationOutcome(true, elapsedMillis(startedAt), fillerFragmentsInserted, pollCount);
            }
        }
        return new RecoveryPreparationOutcome(
                isAnyRecoveryTargetEvicted(recoveryTargetIds),
                elapsedMillis(startedAt),
                fillerFragmentsInserted,
                pollCount);
    }

    private boolean isAnyRecoveryTargetEvicted(List<String> recoveryTargetIds) {
        return recoveryTargetIds.stream().anyMatch(fragmentId -> hmc.getL1().peek(fragmentId).isEmpty());
    }

    RecoveryFillerPlan planRecoveryFillers(String namespace, List<String> recoveryTargetIds) {
        int configuredFillers = Math.max(1, properties.getEvictionFillerFragments());
        if (properties.getEvictionFillerTokens() > 0) {
            return new RecoveryFillerPlan(properties.getEvictionFillerTokens(), configuredFillers);
        }
        long capacity = Math.max(1L, hmc.getL1().maxTokenCapacity());
        long currentNamespaceTokens = hmc.getL1().getAll(namespace).stream()
                .mapToLong(MemoryFragment::getTokenCount)
                .sum();
        long minTargetTokens = resolveMinRecoveryTargetTokens(recoveryTargetIds);
        long preferredMaxFillerTokens = Math.max(1L, minTargetTokens);
        long remainingCapacity = Math.max(0L, capacity - currentNamespaceTokens);
        long minFillerTokensForConfiguredCount = (remainingCapacity / configuredFillers) + 1L;
        if (minFillerTokensForConfiguredCount <= preferredMaxFillerTokens) {
            return new RecoveryFillerPlan((int) minFillerTokensForConfiguredCount, configuredFillers);
        }
        int expandedFillers = (int) ((remainingCapacity / preferredMaxFillerTokens) + 1L);
        return new RecoveryFillerPlan((int) preferredMaxFillerTokens, Math.max(configuredFillers, expandedFillers));
    }

    RecoveryFillerPlan escalateRecoveryFillerPlan(String namespace, RecoveryFillerPlan basePlan, int pressureRound) {
        if (pressureRound <= 0) {
            return basePlan;
        }
        long capacity = Math.max(1L, hmc.getL1().maxTokenCapacity());
        long currentNamespaceTokens = currentNamespaceTokenCount(namespace);
        long doubledTokens = (long) basePlan.fillerTokens() << Math.min(pressureRound, 8);
        long escalatedTokens = Math.max(basePlan.fillerTokens(), doubledTokens);
        if (pressureRound == 1) {
            escalatedTokens = Math.max(escalatedTokens, Math.max(1L, currentNamespaceTokens / 2L));
        } else {
            escalatedTokens = Math.max(escalatedTokens, currentNamespaceTokens);
        }
        return new RecoveryFillerPlan((int) Math.min(capacity, escalatedTokens), 1);
    }

    private long resolveMinRecoveryTargetTokens(List<String> recoveryTargetIds) {
        return recoveryTargetIds.stream()
                .map(fragmentId -> hmc.getL1().peek(fragmentId)
                        .or(() -> l2WarmStore.get(fragmentId)))
                .flatMap(Optional::stream)
                .mapToLong(MemoryFragment::getTokenCount)
                .filter(tokenCount -> tokenCount > 0)
                .min()
                .orElseGet(() -> Math.max(1L, hmc.getL1().maxTokenCapacity() / Math.max(1, properties.getEvictionFillerFragments())));
    }

    private long currentNamespaceTokenCount(String namespace) {
        return hmc.getL1().getAll(namespace).stream()
                .mapToLong(MemoryFragment::getTokenCount)
                .sum();
    }

    private String buildEvictionFillerContent(LlmMemoryEvalCase evalCase, int fillerIndex, int targetTokens) {
        String seed = "eviction filler " + evalCase.getCaseId() + " " + fillerIndex + " distractor";
        StringBuilder builder = new StringBuilder(seed);
        while (tokenCounter.countTokens(builder.toString()) < targetTokens) {
            builder.append(' ').append(seed);
        }
        return builder.toString();
    }

    private WaitOutcome waitForCondition(BooleanSupplier condition, java.util.function.Supplier<String> timeoutMessage) {
        long timeoutMillis = Math.max(0L, properties.getRecoveryPollTimeout().toMillis());
        long deadline = System.currentTimeMillis() + timeoutMillis;
        return waitForConditionUntil(condition, timeoutMessage, deadline);
    }

    private WaitOutcome waitForConditionUntil(
            BooleanSupplier condition,
            java.util.function.Supplier<String> timeoutMessage,
            long deadlineMillis) {
        long startedAt = System.nanoTime();
        if (condition.getAsBoolean()) {
            return new WaitOutcome(true, 0L, 0);
        }

        long intervalMillis = Math.max(1L, properties.getRecoveryPollInterval().toMillis());
        int pollCount = 0;
        while (System.currentTimeMillis() < deadlineMillis) {
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            pause(Math.min(intervalMillis, Math.max(1L, remainingMillis)));
            pollCount++;
            if (condition.getAsBoolean()) {
                return new WaitOutcome(true, elapsedMillis(startedAt), pollCount);
            }
        }
        if (timeoutMessage != null) {
            throw new IllegalStateException(timeoutMessage.get());
        }
        return new WaitOutcome(false, elapsedMillis(startedAt), pollCount);
    }

    private void pause(long intervalMillis) {
        try {
            Thread.sleep(intervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for eval recovery condition", e);
        }
    }

    private String scopedNamespace(LlmMemoryEvalCase evalCase, LlmMemoryEvalMode mode, String runId) {
        return "%s-%s-%s-%s".formatted(
                evalCase.getNamespace(),
                evalCase.getCaseId(),
                mode.name().toLowerCase(),
                runId);
    }

    private String scopedFragmentId(String caseId, LlmMemoryEvalMode mode, String fragmentId, String runId) {
        return "%s::%s::%s::%s".formatted(caseId, mode.name().toLowerCase(), fragmentId, runId);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private void validateCase(LlmMemoryEvalCase evalCase) {
        if (evalCase == null) {
            throw new IllegalArgumentException("Eval case must not be null");
        }
        if (isBlank(evalCase.getCaseId())) {
            throw new IllegalArgumentException("Eval case must define caseId");
        }
        if (isBlank(evalCase.getNamespace())) {
            throw new IllegalArgumentException("Eval case must define namespace");
        }
        if (isBlank(evalCase.getQuestion())) {
            throw new IllegalArgumentException("Eval case must define question");
        }
        if (isBlank(evalCase.getExpectedAnswer())) {
            throw new IllegalArgumentException("Eval case must define expectedAnswer");
        }
        if (safeList(evalCase.getMemoryFragments()).isEmpty()) {
            throw new IllegalArgumentException("Eval case must define at least one memory fragment");
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private List<String> evalMemoryTags(LlmMemoryEvalCase.EvalMemoryFragment fragment) {
        List<String> tags = new ArrayList<>(safeList(fragment.getTags()));
        if (!tags.contains(EVAL_MEMORY_TAG)) {
            tags.add(EVAL_MEMORY_TAG);
        }
        return List.copyOf(tags);
    }

    private List<String> recallTags(LlmMemoryEvalCase evalCase) {
        List<String> tags = new ArrayList<>(safeList(evalCase.getTags()));
        if (!tags.contains(EVAL_MEMORY_TAG)) {
            tags.add(EVAL_MEMORY_TAG);
        }
        return List.copyOf(tags);
    }

    private long delta(Long before, Long after) {
        if (before == null || after == null) {
            return 0L;
        }
        return after - before;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void applyLatencyBreakdown(
            LlmMemoryEvalResult.LlmMemoryEvalResultBuilder builder,
            EvalLatencyBreakdown latencyBreakdown) {
        builder.storeLatencyMs(latencyBreakdown.storeLatencyMs)
                .recoveryTargetWaitLatencyMs(latencyBreakdown.recoveryTargetWaitLatencyMs)
                .recoveryTargetWaitPollCount(latencyBreakdown.recoveryTargetWaitPollCount)
                .recoveryForceLatencyMs(latencyBreakdown.recoveryForceLatencyMs)
                .recoveryForcePollCount(latencyBreakdown.recoveryForcePollCount)
                .recoveryFillerFragmentsInserted(latencyBreakdown.recoveryFillerFragmentsInserted)
                .recallLatencyMs(latencyBreakdown.recallLatencyMs)
                .promptAssemblyLatencyMs(latencyBreakdown.promptAssemblyLatencyMs)
                .generationLatencyMs(latencyBreakdown.generationLatencyMs)
                .generationRequestBuildLatencyMs(latencyBreakdown.generationRequestBuildLatencyMs)
                .generationHttpRoundTripLatencyMs(latencyBreakdown.generationHttpRoundTripLatencyMs)
                .generationResponseParseLatencyMs(latencyBreakdown.generationResponseParseLatencyMs)
                .generationRetryBackoffLatencyMs(latencyBreakdown.generationRetryBackoffLatencyMs)
                .generationLatencyNanos(latencyBreakdown.generationLatencyNanos)
                .generationRequestBuildLatencyNanos(latencyBreakdown.generationRequestBuildLatencyNanos)
                .generationRequestSerializationLatencyNanos(latencyBreakdown.generationRequestSerializationLatencyNanos)
                .generationHttpRequestBuildLatencyNanos(latencyBreakdown.generationHttpRequestBuildLatencyNanos)
                .generationHttpRoundTripLatencyNanos(latencyBreakdown.generationHttpRoundTripLatencyNanos)
                .generationResponseParseLatencyNanos(latencyBreakdown.generationResponseParseLatencyNanos)
                .generationResponseDecodeLatencyNanos(latencyBreakdown.generationResponseDecodeLatencyNanos)
                .generationResponseJsonParseLatencyNanos(latencyBreakdown.generationResponseJsonParseLatencyNanos)
                .generationRetryBackoffLatencyNanos(latencyBreakdown.generationRetryBackoffLatencyNanos)
                .generationAttemptCount(latencyBreakdown.generationAttemptCount)
                .generationHttpStatusCode(latencyBreakdown.generationHttpStatusCode)
                .generationRequestBytes(latencyBreakdown.generationRequestBytes)
                .generationResponseBytes(latencyBreakdown.generationResponseBytes)
                .feedbackLatencyMs(latencyBreakdown.feedbackLatencyMs);
    }

    private void applyGenerationLatencyBreakdown(
            EvalLatencyBreakdown latencyBreakdown,
            GenerationLatencyBreakdown generationLatencyBreakdown) {
        if (generationLatencyBreakdown == null) {
            return;
        }
        latencyBreakdown.generationRequestBuildLatencyMs = generationLatencyBreakdown.getRequestBuildLatencyMs();
        latencyBreakdown.generationHttpRoundTripLatencyMs = generationLatencyBreakdown.getHttpRoundTripLatencyMs();
        latencyBreakdown.generationResponseParseLatencyMs = generationLatencyBreakdown.getResponseParseLatencyMs();
        latencyBreakdown.generationRetryBackoffLatencyMs = generationLatencyBreakdown.getRetryBackoffLatencyMs();
        latencyBreakdown.generationLatencyNanos = generationLatencyBreakdown.totalLatencyNanos();
        latencyBreakdown.generationRequestBuildLatencyNanos = generationLatencyBreakdown.getRequestBuildLatencyNanos();
        latencyBreakdown.generationRequestSerializationLatencyNanos =
                generationLatencyBreakdown.getRequestSerializationLatencyNanos();
        latencyBreakdown.generationHttpRequestBuildLatencyNanos =
                generationLatencyBreakdown.getHttpRequestBuildLatencyNanos();
        latencyBreakdown.generationHttpRoundTripLatencyNanos = generationLatencyBreakdown.getHttpRoundTripLatencyNanos();
        latencyBreakdown.generationResponseParseLatencyNanos = generationLatencyBreakdown.getResponseParseLatencyNanos();
        latencyBreakdown.generationResponseDecodeLatencyNanos = generationLatencyBreakdown.getResponseDecodeLatencyNanos();
        latencyBreakdown.generationResponseJsonParseLatencyNanos =
                generationLatencyBreakdown.getResponseJsonParseLatencyNanos();
        latencyBreakdown.generationRetryBackoffLatencyNanos = generationLatencyBreakdown.getRetryBackoffLatencyNanos();
        latencyBreakdown.generationAttemptCount = generationLatencyBreakdown.getAttemptCount();
        latencyBreakdown.generationHttpStatusCode = generationLatencyBreakdown.getHttpStatusCode();
        latencyBreakdown.generationRequestBytes = generationLatencyBreakdown.getRequestBytes();
        latencyBreakdown.generationResponseBytes = generationLatencyBreakdown.getResponseBytes();
    }

    private void applyGenerationExceptionBreakdown(
            LlmMemoryEvalResult.LlmMemoryEvalResultBuilder builder,
            EvalLatencyBreakdown latencyBreakdown,
            RuntimeException exception) {
        if (!(exception instanceof GenerationException generationException)
                || generationException.getLatencyBreakdown() == null) {
            return;
        }
        applyGenerationLatencyBreakdown(latencyBreakdown, generationException.getLatencyBreakdown());
        applyLatencyBreakdown(builder, latencyBreakdown);
    }

    private String runtimeErrorType(RuntimeException exception) {
        if (exception instanceof GenerationException generationException
                && !isBlank(generationException.getErrorType())) {
            return generationException.getErrorType();
        }
        return RUNTIME_ERROR_RUNNER_INTERNAL;
    }

    private boolean transientRuntimeError(RuntimeException exception) {
        return exception instanceof GenerationException generationException
                && generationException.isTransientError();
    }

    private void logCaseCompletion(
            LlmMemoryEvalCase evalCase,
            LlmMemoryEvalMode mode,
            String namespace,
            RecallResult recallResult,
            Boolean evictedBeforeAnswer,
            EvalLatencyBreakdown latencyBreakdown) {
        log.info(
                "LLM eval case completed caseId={} mode={} namespace={} recallSessionId={} latencyMs={} storeMs={} recoveryTargetWaitMs={} recoveryForceMs={} recallMs={} promptMs={} generationMs={} generationNs={} generationRequestBuildMs={} generationRequestBuildNs={} generationHttpMs={} generationHttpNs={} generationParseMs={} generationParseNs={} generationRetryBackoffMs={} generationRetryBackoffNs={} generationAttemptCount={} generationHttpStatus={} generationRequestBytes={} generationResponseBytes={} feedbackMs={} recoveryTargetWaitPolls={} recoveryForcePolls={} recoveryFillers={} evictedBeforeAnswer={} recallFinalReturnedCount={} recallEmptyReason={} recallL2SearchAccepted={} recallL2FallbackAccepted={}",
                evalCase.getCaseId(),
                mode.reportName(),
                namespace,
                recallResult == null ? null : recallResult.getRecallSessionId(),
                latencyBreakdown.totalLatencyMs(),
                latencyBreakdown.storeLatencyMs,
                latencyBreakdown.recoveryTargetWaitLatencyMs,
                latencyBreakdown.recoveryForceLatencyMs,
                latencyBreakdown.recallLatencyMs,
                latencyBreakdown.promptAssemblyLatencyMs,
                latencyBreakdown.generationLatencyMs,
                latencyBreakdown.generationLatencyNanos,
                latencyBreakdown.generationRequestBuildLatencyMs,
                latencyBreakdown.generationRequestBuildLatencyNanos,
                latencyBreakdown.generationHttpRoundTripLatencyMs,
                latencyBreakdown.generationHttpRoundTripLatencyNanos,
                latencyBreakdown.generationResponseParseLatencyMs,
                latencyBreakdown.generationResponseParseLatencyNanos,
                latencyBreakdown.generationRetryBackoffLatencyMs,
                latencyBreakdown.generationRetryBackoffLatencyNanos,
                latencyBreakdown.generationAttemptCount,
                latencyBreakdown.generationHttpStatusCode,
                latencyBreakdown.generationRequestBytes,
                latencyBreakdown.generationResponseBytes,
                latencyBreakdown.feedbackLatencyMs,
                latencyBreakdown.recoveryTargetWaitPollCount,
                latencyBreakdown.recoveryForcePollCount,
                latencyBreakdown.recoveryFillerFragmentsInserted,
                evictedBeforeAnswer,
                recallResult == null || recallResult.getDiagnostics() == null
                        ? null
                        : recallResult.getDiagnostics().getFinalReturnedCount(),
                recallResult == null || recallResult.getDiagnostics() == null
                        ? null
                        : recallResult.getDiagnostics().getEmptyRecallReason(),
                recallResult == null || recallResult.getDiagnostics() == null
                        ? null
                        : recallResult.getDiagnostics().getL2SearchAcceptedCount(),
                recallResult == null || recallResult.getDiagnostics() == null
                        ? null
                        : recallResult.getDiagnostics().getL2NamespaceFallbackAcceptedCount());
    }

    private record FeedbackObservation(
            boolean submitted,
            List<String> usedFragmentIds,
            Long learningSampleCountBefore,
            Long learningSampleCountAfter,
            Long learningActiveUpdateCountBefore,
            Long learningActiveUpdateCountAfter,
            Double learningShadowLiftBefore,
            Double learningShadowLiftAfter,
            Double learningBaselineLiftBefore,
            Double learningBaselineLiftAfter) {

        private static FeedbackObservation notSubmitted() {
            return new FeedbackObservation(false, List.of(), null, null, null, null, null, null, null, null);
        }

        private static FeedbackObservation submitted(
                List<String> usedFragmentIds,
                AdaptiveWeightLearner.LearningSnapshot before,
                AdaptiveWeightLearner.LearningSnapshot after) {
            return new FeedbackObservation(
                    true,
                    usedFragmentIds == null ? List.of() : List.copyOf(usedFragmentIds),
                    sampleCount(before),
                    sampleCount(after),
                    activeUpdateCount(before),
                    activeUpdateCount(after),
                    shadowLift(before),
                    shadowLift(after),
                    baselineLift(before),
                    baselineLift(after));
        }

        private static Long sampleCount(AdaptiveWeightLearner.LearningSnapshot snapshot) {
            return snapshot == null || snapshot.shadowEvaluation() == null
                    ? null
                    : snapshot.shadowEvaluation().sampleCount();
        }

        private static Long activeUpdateCount(AdaptiveWeightLearner.LearningSnapshot snapshot) {
            return snapshot == null || snapshot.active() == null
                    ? null
                    : snapshot.active().getUpdateCount();
        }

        private static Double shadowLift(AdaptiveWeightLearner.LearningSnapshot snapshot) {
            return snapshot == null || snapshot.shadowEvaluation() == null
                    ? null
                    : snapshot.shadowEvaluation().relativeLift();
        }

        private static Double baselineLift(AdaptiveWeightLearner.LearningSnapshot snapshot) {
            return snapshot == null || snapshot.shadowEvaluation() == null
                    ? null
                    : snapshot.shadowEvaluation().baselineRelativeLift();
        }
    }

    private record WaitOutcome(boolean satisfied, long elapsedMs, int pollCount) {
    }

    private record RecoveryPreparationOutcome(
            boolean evictedBeforeAnswer,
            long elapsedMs,
            int fillerFragmentsInserted,
            int pollCount) {
    }

    record RecoveryFillerPlan(int fillerTokens, int maxFillers) {
    }

    private static final class EvalLatencyBreakdown {
        private long storeLatencyMs;
        private long recoveryTargetWaitLatencyMs;
        private int recoveryTargetWaitPollCount;
        private long recoveryForceLatencyMs;
        private int recoveryForcePollCount;
        private int recoveryFillerFragmentsInserted;
        private long recallLatencyMs;
        private long promptAssemblyLatencyMs;
        private long generationLatencyMs;
        private long generationRequestBuildLatencyMs;
        private long generationHttpRoundTripLatencyMs;
        private long generationResponseParseLatencyMs;
        private long generationRetryBackoffLatencyMs;
        private long generationLatencyNanos;
        private long generationRequestBuildLatencyNanos;
        private long generationRequestSerializationLatencyNanos;
        private long generationHttpRequestBuildLatencyNanos;
        private long generationHttpRoundTripLatencyNanos;
        private long generationResponseParseLatencyNanos;
        private long generationResponseDecodeLatencyNanos;
        private long generationResponseJsonParseLatencyNanos;
        private long generationRetryBackoffLatencyNanos;
        private Integer generationAttemptCount;
        private Integer generationHttpStatusCode;
        private Integer generationRequestBytes;
        private Integer generationResponseBytes;
        private long feedbackLatencyMs;

        private long totalLatencyMs() {
            return storeLatencyMs
                    + recoveryTargetWaitLatencyMs
                    + recoveryForceLatencyMs
                    + recallLatencyMs
                    + promptAssemblyLatencyMs
                    + generationLatencyMs
                    + feedbackLatencyMs;
        }
    }
}
