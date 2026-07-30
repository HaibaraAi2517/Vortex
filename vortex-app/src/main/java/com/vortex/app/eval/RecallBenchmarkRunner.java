package com.vortex.app.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.storage.api.L2WarmStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecallBenchmarkRunner {

    private static final TypeReference<List<LlmMemoryEvalCase>> CASE_SET_TYPE = new TypeReference<>() {};
    private static final String EVAL_MEMORY_TAG = "llm-memory-eval-memory";
    private static final List<Integer> DEFAULT_EVALUATION_KS = List.of(1, 3, 5, 10);

    private final HierarchicalMemoryController hmc;
    private final L2WarmStore l2WarmStore;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final LlmMemoryEvalProperties properties;

    @Qualifier("bgeSmallEmbeddingService")
    private final TokenCounter tokenCounter;
    private final LlmMemoryEvalEnvironmentSnapshotFactory environmentSnapshotFactory;

    public RecallBenchmarkReport runConfiguredBenchmark() {
        return runAblation(loadCaseSet(properties.getDatasetLocation()), configuredAblationModes());
    }

    public List<LlmMemoryEvalCase> loadCaseSet(String datasetLocation) {
        if (isBlank(datasetLocation)) {
            throw new IllegalArgumentException("Recall benchmark dataset location must not be blank");
        }
        Resource resource = resourceLoader.getResource(datasetLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("Recall benchmark dataset not found: " + datasetLocation);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            List<LlmMemoryEvalCase> cases = objectMapper.readValue(inputStream, CASE_SET_TYPE);
            log.info("Loaded recall benchmark dataset location={} cases={}", datasetLocation, cases.size());
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load recall benchmark dataset from " + datasetLocation, e);
        }
    }

    public RecallBenchmarkReport run(List<LlmMemoryEvalCase> cases, Collection<LlmMemoryEvalMode> modes) {
        return runAblation(cases, ablationModesFromEvalModes(modes));
    }

    public RecallBenchmarkReport runAblation(List<LlmMemoryEvalCase> cases, Collection<RecallAblationMode> modes) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Recall benchmark requires at least one case");
        }
        List<RecallAblationMode> modeList = normalizeAblationModes(modes);
        if (modeList.isEmpty()) {
            throw new IllegalArgumentException("Recall benchmark requires at least one recall ablation mode");
        }

        String runId = UUID.randomUUID().toString().substring(0, 8);
        List<Integer> evaluationKs = evaluationKs();
        int maxTopK = evaluationKs.stream().mapToInt(Integer::intValue).max().orElse(Math.max(1, properties.getRecallTopK()));
        List<RecallBenchmarkReport.CaseResult> results = new ArrayList<>();
        Map<String, List<LlmMemoryEvalCase>> casesByNamespace = cases.stream()
                .peek(this::validateCase)
                .collect(Collectors.groupingBy(
                        LlmMemoryEvalCase::getNamespace,
                        LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<String, List<LlmMemoryEvalCase>> entry : casesByNamespace.entrySet()) {
            String namespace = scopedNamespace(entry.getKey(), runId);
            StoredCase storedCase = storeCaseFragments(entry.getValue(), namespace, runId);
            try {
                waitForStoredFragments(storedCase.storedFragmentIds(), namespace);
                for (LlmMemoryEvalCase evalCase : entry.getValue()) {
                    for (RecallAblationMode mode : modeList) {
                        results.add(runSingleCase(evalCase, mode, namespace, storedCase.actualToLogicalIds(), evaluationKs, maxTopK));
                    }
                }
            } finally {
                cleanupStoredFragments(storedCase.storedFragmentIds());
            }
        }

        int primaryTopK = primaryTopK(evaluationKs);
        return RecallBenchmarkReport.builder()
                .generatedAt(Instant.now())
                .runId(runId)
                .datasetLocation(properties.getDatasetLocation())
                .totalCases(cases.size())
                .totalRuns(results.size())
                .topK(primaryTopK)
                .evaluationKs(evaluationKs)
                .tokenBudget(properties.getRecallTokenBudget())
                .modes(modeList.stream().map(RecallAblationMode::reportName).toList())
                .environmentSnapshot(environmentSnapshotFactory.snapshot())
                .results(List.copyOf(results))
                .modeSummaries(buildModeSummaries(results, evaluationKs, primaryTopK))
                .build();
    }

    private List<RecallAblationMode> configuredAblationModes() {
        List<RecallAblationMode> explicitlyConfigured = normalizeAblationModes(
                properties.getRecallAblationModes());
        if (!explicitlyConfigured.isEmpty()) {
            return explicitlyConfigured;
        }
        return List.of(
                RecallAblationMode.KEYWORD_ONLY,
                RecallAblationMode.VECTOR_ONLY,
                RecallAblationMode.VECTOR_RERANK,
                RecallAblationMode.HYBRID,
                RecallAblationMode.HYBRID_RERANK);
    }

    private List<RecallAblationMode> ablationModesFromEvalModes(Collection<LlmMemoryEvalMode> modes) {
        if (modes == null) {
            return configuredAblationModes();
        }
        LinkedHashSet<RecallAblationMode> normalized = new LinkedHashSet<>();
        for (LlmMemoryEvalMode mode : modes) {
            if (mode == LlmMemoryEvalMode.VORTEX_VECTOR_ONLY) {
                normalized.add(RecallAblationMode.VECTOR_RERANK);
            } else if (mode == LlmMemoryEvalMode.VORTEX_MEMORY) {
                normalized.add(RecallAblationMode.HYBRID_RERANK);
            }
        }
        return normalized.isEmpty() ? configuredAblationModes() : List.copyOf(normalized);
    }

    private List<RecallAblationMode> normalizeAblationModes(Collection<RecallAblationMode> modes) {
        if (modes == null) {
            return List.of();
        }
        LinkedHashSet<RecallAblationMode> normalized = new LinkedHashSet<>();
        for (RecallAblationMode mode : modes) {
            if (mode != null) {
                normalized.add(mode);
            }
        }
        return List.copyOf(normalized);
    }

    private StoredCase storeCaseFragments(List<LlmMemoryEvalCase> cases, String namespace, String runId) {
        List<String> storedFragmentIds = new ArrayList<>();
        Map<String, String> actualToLogicalIds = new LinkedHashMap<>();
        for (LlmMemoryEvalCase evalCase : cases) {
            validateCase(evalCase);
            for (LlmMemoryEvalCase.EvalMemoryFragment fragment : safeList(evalCase.getMemoryFragments())) {
                if (fragment == null || isBlank(fragment.getFragmentId()) || isBlank(fragment.getContent())) {
                    continue;
                }
                String actualFragmentId = scopedFragmentId(evalCase.getCaseId(), fragment.getFragmentId(), runId);
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
        }
        if (storedFragmentIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Recall benchmark namespace '%s' did not yield any storable memory fragments"
                            .formatted(namespace));
        }
        return new StoredCase(List.copyOf(storedFragmentIds), Map.copyOf(actualToLogicalIds));
    }

    private RecallBenchmarkReport.CaseResult runSingleCase(
            LlmMemoryEvalCase evalCase,
            RecallAblationMode mode,
            String namespace,
            Map<String, String> actualToLogicalIds,
            List<Integer> evaluationKs,
            int maxTopK) {
        long startedAt = System.nanoTime();
        List<String> expectedFragments = safeList(evalCase.getExpectedFragments());
        int primaryTopK = primaryTopK(evaluationKs);
        try {
            clearL1(namespace);
            RecallResult recallResult = hmc.recall(RecallQuery.builder()
                    .query(evalCase.getQuestion())
                    .namespace(namespace)
                    .topK(maxTopK)
                    .tokenBudget(properties.getRecallTokenBudget())
                    .scenario(properties.getLearningScenario())
                    .retrievalMode(mode.retrievalMode())
                    .rerankEnabled(mode.rerankEnabled())
                    .rerankerType(mode.rerankerType())
                    .tags(recallTags(evalCase))
                    .build());
            List<String> returnedFragmentIds = mapReturnedFragmentIds(recallResult, actualToLogicalIds);
            List<String> returnedTiers = mapReturnedTiers(recallResult);
            Map<Integer, RecallBenchmarkReport.MetricAtK> metricsByK = computeMetricsByK(
                    returnedFragmentIds,
                    expectedFragments,
                    evaluationKs);
            RecallBenchmarkReport.MetricAtK primaryMetrics = metricAtK(metricsByK, primaryTopK);
            return RecallBenchmarkReport.CaseResult.builder()
                    .caseId(evalCase.getCaseId())
                    .mode(mode.reportName())
                    .retrievalMode(mode.retrievalMode().name())
                    .rerankEnabled(mode.rerankEnabled())
                    .rerankerType(mode.rerankerType())
                    .namespace(namespace)
                    .question(evalCase.getQuestion())
                    .expectedFragmentIds(expectedFragments)
                    .returnedFragmentIds(returnedFragmentIds)
                    .returnedTiers(returnedTiers)
                    .recallHit(primaryMetrics.isRecallHit())
                    .allExpectedReturned(primaryMetrics.isAllExpectedReturned())
                    .recallAtK(primaryMetrics.getRecall())
                    .precisionAtK(primaryMetrics.getPrecision())
                    .reciprocalRank(primaryMetrics.getReciprocalRank())
                    .ndcg(primaryMetrics.getNdcg())
                    .latencyMs(elapsedMillis(startedAt))
                    .metricsByK(metricsByK)
                    .recallDiagnostics(recallResult == null ? null : recallResult.getDiagnostics())
                    .build();
        } catch (RuntimeException e) {
            log.error("Recall benchmark case failed caseId={} mode={} namespace={}",
                    evalCase.getCaseId(), mode.reportName(), namespace, e);
            return RecallBenchmarkReport.CaseResult.builder()
                    .caseId(evalCase.getCaseId())
                    .mode(mode.reportName())
                    .retrievalMode(mode.retrievalMode().name())
                    .rerankEnabled(mode.rerankEnabled())
                    .rerankerType(mode.rerankerType())
                    .namespace(namespace)
                    .question(evalCase.getQuestion())
                    .expectedFragmentIds(expectedFragments)
                    .returnedFragmentIds(List.of())
                    .returnedTiers(List.of())
                    .metricsByK(emptyMetricsByK(evaluationKs))
                    .latencyMs(elapsedMillis(startedAt))
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private Map<String, RecallBenchmarkReport.ModeSummary> buildModeSummaries(
            List<RecallBenchmarkReport.CaseResult> results,
            List<Integer> evaluationKs,
            int primaryTopK) {
        Map<String, RecallBenchmarkReport.ModeSummary> summaries = results.stream()
                .collect(Collectors.groupingBy(
                        RecallBenchmarkReport.CaseResult::getMode,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), grouped -> {
                            int total = grouped.size();
                            long errors = grouped.stream()
                                    .filter(result -> !isBlank(result.getErrorMessage()))
                                    .count();
                            Map<Integer, RecallBenchmarkReport.MetricAtK> metricsByK = buildMetricsByK(grouped, evaluationKs);
                            RecallBenchmarkReport.MetricAtK primary = metricAtK(metricsByK, primaryTopK);
                            return RecallBenchmarkReport.ModeSummary.builder()
                                    .total(total)
                                    .errors((int) errors)
                                    .caseHitRate(rate(grouped, RecallBenchmarkReport.CaseResult::isRecallHit))
                                    .allExpectedHitRate(rate(grouped, RecallBenchmarkReport.CaseResult::isAllExpectedReturned))
                                    .recallAtK(primary.getRecall())
                                    .precisionAtK(primary.getPrecision())
                                    .mrr(primary.getReciprocalRank())
                                    .ndcg(primary.getNdcg())
                                    .averageLatencyMs(grouped.stream()
                                            .mapToLong(RecallBenchmarkReport.CaseResult::getLatencyMs)
                                            .average()
                                            .orElse(0.0d))
                                    .latencyP50Ms(percentileLatency(grouped, 0.50d))
                                    .latencyP95Ms(percentileLatency(grouped, 0.95d))
                                    .latencyP99Ms(percentileLatency(grouped, 0.99d))
                                    .metricsByK(metricsByK)
                                    .build();
                        })));
        applyVectorOnlyLift(summaries, RecallAblationMode.VECTOR_RERANK.reportName());
        return summaries;
    }

    private Map<Integer, RecallBenchmarkReport.MetricAtK> buildMetricsByK(
            List<RecallBenchmarkReport.CaseResult> grouped,
            List<Integer> evaluationKs) {
        Map<Integer, RecallBenchmarkReport.MetricAtK> metrics = new LinkedHashMap<>();
        for (int k : evaluationKs) {
            List<RecallBenchmarkReport.MetricAtK> caseMetrics = grouped.stream()
                    .map(RecallBenchmarkReport.CaseResult::getMetricsByK)
                    .map(map -> map == null ? null : map.get(k))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            int total = caseMetrics.size();
            long caseHits = caseMetrics.stream().filter(RecallBenchmarkReport.MetricAtK::isRecallHit).count();
            long allExpectedHits = caseMetrics.stream().filter(RecallBenchmarkReport.MetricAtK::isAllExpectedReturned).count();
            metrics.put(k, RecallBenchmarkReport.MetricAtK.builder()
                    .recallHit(total > 0 && caseHits == total)
                    .allExpectedReturned(total > 0 && allExpectedHits == total)
                    .recall(averageMetric(caseMetrics, RecallBenchmarkReport.MetricAtK::getRecall))
                    .precision(averageMetric(caseMetrics, RecallBenchmarkReport.MetricAtK::getPrecision))
                    .reciprocalRank(averageMetric(caseMetrics, RecallBenchmarkReport.MetricAtK::getReciprocalRank))
                    .ndcg(averageMetric(caseMetrics, RecallBenchmarkReport.MetricAtK::getNdcg))
                    .build());
        }
        return metrics;
    }

    private double rate(
            List<RecallBenchmarkReport.CaseResult> results,
            java.util.function.Predicate<RecallBenchmarkReport.CaseResult> predicate) {
        return results.isEmpty() ? 0.0d : (double) results.stream().filter(predicate).count() / results.size();
    }

    private double percentileLatency(
            List<RecallBenchmarkReport.CaseResult> results,
            double percentile) {
        if (results == null || results.isEmpty()) {
            return 0.0d;
        }
        List<Long> sorted = results.stream()
                .map(RecallBenchmarkReport.CaseResult::getLatencyMs)
                .sorted()
                .toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double averageMetric(
            List<RecallBenchmarkReport.MetricAtK> metrics,
            java.util.function.ToDoubleFunction<RecallBenchmarkReport.MetricAtK> metric) {
        return metrics.stream().mapToDouble(metric).average().orElse(0.0d);
    }

    private void applyVectorOnlyLift(Map<String, RecallBenchmarkReport.ModeSummary> summaries, String baselineMode) {
        RecallBenchmarkReport.ModeSummary vectorOnly = summaries.get(baselineMode);
        if (vectorOnly == null) {
            return;
        }
        double baselineRecallAtK = vectorOnly.getRecallAtK();
        double baselineCaseHitRate = vectorOnly.getCaseHitRate();
        for (RecallBenchmarkReport.ModeSummary summary : summaries.values()) {
            double recallLift = summary.getRecallAtK() - baselineRecallAtK;
            summary.setRecallAtKLiftVsVectorOnly(recallLift);
            summary.setRecallAtKRelativeLiftVsVectorOnly(
                    baselineRecallAtK == 0.0d ? 0.0d : recallLift / baselineRecallAtK);
            double caseHitLift = summary.getCaseHitRate() - baselineCaseHitRate;
            summary.setCaseHitRateLiftVsVectorOnly(caseHitLift);
            summary.setCaseHitRateRelativeLiftVsVectorOnly(
                    baselineCaseHitRate == 0.0d ? 0.0d : caseHitLift / baselineCaseHitRate);
        }
    }

    private Map<Integer, RecallBenchmarkReport.MetricAtK> computeMetricsByK(
            List<String> returnedFragmentIds,
            List<String> expectedFragments,
            List<Integer> evaluationKs) {
        Map<Integer, RecallBenchmarkReport.MetricAtK> metrics = new LinkedHashMap<>();
        for (int k : evaluationKs) {
            metrics.put(k, computeMetrics(returnedFragmentIds, expectedFragments, k));
        }
        return metrics;
    }

    private Map<Integer, RecallBenchmarkReport.MetricAtK> emptyMetricsByK(List<Integer> evaluationKs) {
        Map<Integer, RecallBenchmarkReport.MetricAtK> metrics = new LinkedHashMap<>();
        for (int k : evaluationKs) {
            metrics.put(k, RecallBenchmarkReport.MetricAtK.builder().build());
        }
        return metrics;
    }

    private RecallBenchmarkReport.MetricAtK metricAtK(Map<Integer, RecallBenchmarkReport.MetricAtK> metricsByK, int k) {
        RecallBenchmarkReport.MetricAtK metric = metricsByK == null ? null : metricsByK.get(k);
        return metric == null ? RecallBenchmarkReport.MetricAtK.builder().build() : metric;
    }

    private RecallBenchmarkReport.MetricAtK computeMetrics(
            List<String> returnedFragmentIds,
            List<String> expectedFragments,
            int topK) {
        Set<String> expectedSet = new HashSet<>(safeList(expectedFragments));
        if (expectedSet.isEmpty()) {
            return RecallBenchmarkReport.MetricAtK.builder().build();
        }
        int boundedTopK = Math.max(1, topK);
        List<String> returned = safeList(returnedFragmentIds).stream()
                .limit(boundedTopK)
                .toList();
        long matched = returned.stream().filter(expectedSet::contains).distinct().count();
        boolean caseHit = matched > 0;
        boolean allExpectedHit = returned.containsAll(expectedSet);
        double recallAtK = (double) matched / expectedSet.size();
        double precisionAtK = (double) matched / boundedTopK;
        double reciprocalRank = reciprocalRank(returned, expectedSet);
        double ndcg = ndcg(returned, expectedSet, boundedTopK);
        return RecallBenchmarkReport.MetricAtK.builder()
                .recallHit(caseHit)
                .allExpectedReturned(allExpectedHit)
                .recall(recallAtK)
                .precision(precisionAtK)
                .reciprocalRank(reciprocalRank)
                .ndcg(ndcg)
                .build();
    }

    private double reciprocalRank(List<String> returnedFragmentIds, Set<String> expectedFragments) {
        for (int index = 0; index < returnedFragmentIds.size(); index++) {
            if (expectedFragments.contains(returnedFragmentIds.get(index))) {
                return 1.0d / (index + 1);
            }
        }
        return 0.0d;
    }

    private double ndcg(List<String> returnedFragmentIds, Set<String> expectedFragments, int topK) {
        int boundedTopK = Math.max(1, topK);
        double dcg = 0.0d;
        for (int index = 0; index < Math.min(returnedFragmentIds.size(), boundedTopK); index++) {
            if (expectedFragments.contains(returnedFragmentIds.get(index))) {
                dcg += 1.0d / log2(index + 2.0d);
            }
        }
        int idealRelevant = Math.min(expectedFragments.size(), boundedTopK);
        double idcg = 0.0d;
        for (int index = 0; index < idealRelevant; index++) {
            idcg += 1.0d / log2(index + 2.0d);
        }
        return idcg == 0.0d ? 0.0d : dcg / idcg;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0d);
    }

    private void waitForStoredFragments(List<String> storedFragmentIds, String namespace) {
        waitForCondition(
                () -> storedFragmentIds.stream().allMatch(fragmentId -> l2WarmStore.get(fragmentId)
                        .filter(fragment -> namespace.equals(fragment.getNamespace()))
                        .isPresent()),
                () -> "Timed out waiting for recall benchmark fragments in L2 namespace=%s fragmentIds=%s"
                        .formatted(namespace, storedFragmentIds));
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
            throw new IllegalStateException("Interrupted while waiting for recall benchmark condition", e);
        }
    }

    private void clearL1(String namespace) {
        hmc.getL1().clear(namespace);
    }

    private void cleanupStoredFragments(List<String> storedFragmentIds) {
        for (String fragmentId : storedFragmentIds) {
            try {
                hmc.deleteFragment(fragmentId);
            } catch (RuntimeException e) {
                log.warn("Failed to clean recall benchmark fragment fragmentId={}: {}", fragmentId, e.getMessage());
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

    private void validateCase(LlmMemoryEvalCase evalCase) {
        if (evalCase == null) {
            throw new IllegalArgumentException("Recall benchmark case must not be null");
        }
        if (isBlank(evalCase.getCaseId())) {
            throw new IllegalArgumentException("Recall benchmark case must define caseId");
        }
        if (isBlank(evalCase.getNamespace())) {
            throw new IllegalArgumentException("Recall benchmark case must define namespace");
        }
        if (isBlank(evalCase.getQuestion())) {
            throw new IllegalArgumentException("Recall benchmark case must define question");
        }
        if (safeList(evalCase.getMemoryFragments()).isEmpty()) {
            throw new IllegalArgumentException("Recall benchmark case must define at least one memory fragment");
        }
        if (safeList(evalCase.getExpectedFragments()).isEmpty()) {
            throw new IllegalArgumentException("Recall benchmark case must define expectedFragments");
        }
    }

    private String scopedNamespace(String sourceNamespace, String runId) {
        return "%s-recall-benchmark-%s".formatted(sourceNamespace, runId);
    }

    private String scopedFragmentId(String caseId, String fragmentId, String runId) {
        return "%s::recall_benchmark::%s::%s".formatted(caseId, fragmentId, runId);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private List<Integer> evaluationKs() {
        LinkedHashSet<Integer> ks = new LinkedHashSet<>(DEFAULT_EVALUATION_KS);
        ks.add(Math.max(1, properties.getRecallTopK()));
        return ks.stream()
                .filter(k -> k != null && k > 0)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private int primaryTopK(List<Integer> evaluationKs) {
        int configured = Math.max(1, properties.getRecallTopK());
        List<Integer> safeKs = safeList(evaluationKs);
        return safeKs.contains(configured) ? configured : safeKs.getLast();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record StoredCase(List<String> storedFragmentIds, Map<String, String> actualToLogicalIds) {
    }
}
