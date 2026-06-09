package com.vortex.app.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.hmc.AdaptiveWeightLearner;
import com.vortex.kernel.hmc.AdaptiveWeightProfile;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.hmc.ShadowEvaluationTracker;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningMemoryEvalRunner {

    private static final TypeReference<List<LearningMemoryEvalCase>> CASE_SET_TYPE = new TypeReference<>() {};
    private static final String LEARNING_EVAL_TAG = "learning-memory-eval";

    private final HierarchicalMemoryController hmc;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final LearningMemoryEvalProperties properties;
    private final LearningMemoryEvalGateEvaluator gateEvaluator;

    @Qualifier("bgeSmallEmbeddingService")
    private final TokenCounter tokenCounter;

    public List<LearningMemoryEvalCase> loadDefaultCaseSet() {
        return loadCaseSet(properties.getDatasetLocation());
    }

    public List<LearningMemoryEvalCase> loadCaseSet(String datasetLocation) {
        if (isBlank(datasetLocation)) {
            throw new IllegalArgumentException("Learning eval dataset location must not be blank");
        }
        Resource resource = resourceLoader.getResource(datasetLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("Learning eval dataset not found: " + datasetLocation);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            List<LearningMemoryEvalCase> cases = objectMapper.readValue(inputStream, CASE_SET_TYPE);
            log.info("Loaded learning memory eval dataset location={} scenarios={}",
                    datasetLocation, cases.size());
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load learning eval dataset from " + datasetLocation, e);
        }
    }

    public LearningMemoryEvalReport runConfiguredProfile() {
        return run(loadDefaultCaseSet());
    }

    public LearningMemoryEvalReport run(List<LearningMemoryEvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Learning eval runner requires at least one scenario");
        }
        List<LearningMemoryEvalCase> caseList = List.copyOf(cases);
        caseList.forEach(this::validateCase);

        String runId = UUID.randomUUID().toString().substring(0, 8);
        MemoryScenario aggregateScenario = firstScenario(caseList);
        AdaptiveWeightLearner.LearningSnapshot before = hmc.learningSnapshot(aggregateScenario);
        List<ScenarioExecution> executions = caseList.stream()
                .map(evalCase -> prepareScenario(evalCase, runId))
                .toList();
        executions.forEach(this::storeFragments);
        executions.forEach(this::captureBeforeSnapshot);
        executions.forEach(this::recallInitialCalibration);
        executions.forEach(this::submitInitialCalibrationFeedback);
        executions.forEach(this::runRemainingCalibrationAndProbe);
        List<LearningMemoryEvalReport.ScenarioResult> scenarioResults = executions.stream()
                .map(this::buildScenarioResult)
                .toList();
        AdaptiveWeightLearner.LearningSnapshot after = hmc.learningSnapshot(aggregateScenario);

        LearningMemoryEvalReport.LearningAggregate aggregate = buildAggregate(
                scenarioResults,
                summarizeSnapshot(before),
                summarizeSnapshot(after));
        LearningMemoryEvalReport report = LearningMemoryEvalReport.builder()
                .generatedAt(Instant.now())
                .runId(runId)
                .profileId(properties.getProfileId())
                .datasetLocation(properties.getDatasetLocation())
                .datasetVersion(properties.getDatasetVersion())
                .scenarioCount(caseList.size())
                .totalRecallCount(scenarioResults.stream().mapToInt(LearningMemoryEvalReport.ScenarioResult::getRecallCount).sum())
                .feedbackSubmitted(scenarioResults.stream().mapToInt(LearningMemoryEvalReport.ScenarioResult::getFeedbackSubmitted).sum())
                .aggregate(aggregate)
                .scenarios(List.copyOf(scenarioResults))
                .build();
        List<LearningMemoryEvalReport.GateCheck> checks = gateEvaluator.evaluate(report);
        report.setGateChecks(checks);
        report.setGatePassed(gateEvaluator.passed(checks));
        return report;
    }

    private ScenarioExecution prepareScenario(LearningMemoryEvalCase evalCase, String runId) {
        MemoryScenario scenario = effectiveScenario(evalCase);
        String actualNamespace = scopedNamespace(evalCase, runId);
        Map<String, String> logicalToActual = new LinkedHashMap<>();
        Map<String, String> actualToLogical = new LinkedHashMap<>();
        Set<String> relevantIds = relevantFragmentIds(evalCase);
        List<String> feedbackIds = feedbackLogicalIds(evalCase, relevantIds);
        return new ScenarioExecution(
                evalCase,
                scenario,
                actualNamespace,
                runId,
                logicalToActual,
                actualToLogical,
                relevantIds,
                feedbackIds,
                new ArrayList<>());
    }

    private void storeFragments(ScenarioExecution execution) {
        storeFragments(
                execution.evalCase(),
                execution.actualNamespace(),
                execution.runId(),
                execution.logicalToActual(),
                execution.actualToLogical());
    }

    private void captureBeforeSnapshot(ScenarioExecution execution) {
        execution.beforeSnapshot = summarizeSnapshot(hmc.learningSnapshot(execution.scenario()));
    }

    private void recallInitialCalibration(ScenarioExecution execution) {
        String query = safeList(execution.evalCase().getCalibrationQueries()).get(0);
        LearningMemoryEvalReport.RecallObservation observation = recall(
                execution.evalCase(),
                execution.actualNamespace(),
                execution.scenario(),
                query,
                "before-calibration",
                execution.relevantIds(),
                execution.actualToLogical());
        execution.observations().add(observation);
    }

    private void submitInitialCalibrationFeedback(ScenarioExecution execution) {
        LearningMemoryEvalReport.RecallObservation initialObservation = execution.observations().stream()
                .filter(observation -> "before-calibration".equals(observation.getPhase()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing before-calibration observation for scenario " + execution.evalCase().getScenarioId()));
        submitFeedback(
                initialObservation.getRecallSessionId(),
                execution.feedbackIds(),
                execution.logicalToActual(),
                execution.evalCase());
        execution.feedbackSubmitted++;
    }

    private void runRemainingCalibrationAndProbe(ScenarioExecution execution) {
        List<String> calibrationQueries = safeList(execution.evalCase().getCalibrationQueries());
        for (int index = 1; index < calibrationQueries.size(); index++) {
            LearningMemoryEvalReport.RecallObservation observation = recall(
                    execution.evalCase(),
                    execution.actualNamespace(),
                    execution.scenario(),
                    calibrationQueries.get(index),
                    "calibration-feedback",
                    execution.relevantIds(),
                    execution.actualToLogical());
            execution.observations().add(observation);
            submitFeedback(
                    observation.getRecallSessionId(),
                    execution.feedbackIds(),
                    execution.logicalToActual(),
                    execution.evalCase());
            execution.feedbackSubmitted++;
        }
        for (String query : safeList(execution.evalCase().getProbeQueries())) {
            LearningMemoryEvalReport.RecallObservation observation = recall(
                    execution.evalCase(),
                    execution.actualNamespace(),
                    execution.scenario(),
                    query,
                    "probe",
                    execution.relevantIds(),
                    execution.actualToLogical());
            execution.observations().add(observation);
            submitFeedback(
                    observation.getRecallSessionId(),
                    execution.feedbackIds(),
                    execution.logicalToActual(),
                    execution.evalCase());
            execution.feedbackSubmitted++;
        }
    }

    private LearningMemoryEvalReport.ScenarioResult buildScenarioResult(ScenarioExecution execution) {
        LearningMemoryEvalReport.LearningProfileSnapshot after =
                summarizeSnapshot(hmc.learningSnapshot(execution.scenario()));
        List<LearningMemoryEvalReport.RecallObservation> beforeObservations = execution.observations().stream()
                .filter(observation -> "before-calibration".equals(observation.getPhase()))
                .toList();
        List<LearningMemoryEvalReport.RecallObservation> probeObservations = execution.observations().stream()
                .filter(observation -> "probe".equals(observation.getPhase()))
                .toList();
        double beforeMedianRank = medianMetric(beforeObservations,
                LearningMemoryEvalReport.RecallObservation::getMedianRelevantRank);
        double afterMedianRank = medianMetric(probeObservations,
                LearningMemoryEvalReport.RecallObservation::getMedianRelevantRank);
        double firstCalibrationNdcg = averageMetric(beforeObservations,
                LearningMemoryEvalReport.RecallObservation::getNdcg);
        double probeAverageNdcg = averageMetric(probeObservations,
                LearningMemoryEvalReport.RecallObservation::getNdcg);
        return LearningMemoryEvalReport.ScenarioResult.builder()
                .scenarioId(execution.evalCase().getScenarioId())
                .namespace(execution.evalCase().getNamespace())
                .actualNamespace(execution.actualNamespace())
                .memoryScenario(execution.scenario().name())
                .fragmentCount(safeList(execution.evalCase().getFragments()).size())
                .relevantFragmentIds(List.copyOf(execution.relevantIds()))
                .recallCount(execution.observations().size())
                .feedbackSubmitted(execution.feedbackSubmitted)
                .beforeMedianRelevantRank(beforeMedianRank)
                .afterMedianRelevantRank(afterMedianRank)
                .firstCalibrationNdcg(firstCalibrationNdcg)
                .probeAverageNdcg(probeAverageNdcg)
                .rankImproved(afterMedianRank < beforeMedianRank)
                .ndcgImproved(probeAverageNdcg > firstCalibrationNdcg)
                .probeAllRelevantHitRate(rate(probeObservations,
                        LearningMemoryEvalReport.RecallObservation::isAllRelevantHit))
                .activeSelectionPrecisionAfter(averageMetric(probeObservations,
                        LearningMemoryEvalReport.RecallObservation::getSelectionPrecision))
                .activeSelectionCoverageAfter(averageMetric(probeObservations,
                        LearningMemoryEvalReport.RecallObservation::getSelectionCoverage))
                .sampleCountBefore(nullToZero(execution.beforeSnapshot.getShadowSampleCount()))
                .sampleCountAfter(nullToZero(after.getShadowSampleCount()))
                .activeUpdateCountBefore(nullToZero(execution.beforeSnapshot.getActiveUpdateCount()))
                .activeUpdateCountAfter(nullToZero(after.getActiveUpdateCount()))
                .activeAverageNdcgBefore(nullToZero(execution.beforeSnapshot.getActiveAverageNdcg()))
                .activeAverageNdcgAfter(nullToZero(after.getActiveAverageNdcg()))
                .shadowAverageNdcgAfter(nullToZero(after.getShadowAverageNdcg()))
                .baselineAverageNdcgAfter(nullToZero(after.getBaselineAverageNdcg()))
                .activeEvictionUtilityAfter(nullToZero(after.getActiveEvictionUtility()))
                .shadowEvictionUtilityAfter(nullToZero(after.getShadowEvictionUtility()))
                .baselineEvictionUtilityAfter(nullToZero(after.getBaselineEvictionUtility()))
                .shadowRelativeLiftAfter(nullToZero(after.getShadowRelativeLift()))
                .baselineRelativeLiftAfter(nullToZero(after.getBaselineRelativeLift()))
                .observations(List.copyOf(execution.observations()))
                .build();
    }

    private void storeFragments(
            LearningMemoryEvalCase evalCase,
            String actualNamespace,
            String runId,
            Map<String, String> logicalToActual,
            Map<String, String> actualToLogical) {
        for (LearningMemoryEvalCase.LearningMemoryFragment source : safeList(evalCase.getFragments())) {
            String actualId = scopedFragmentId(evalCase.getScenarioId(), source.getFragmentId(), runId);
            logicalToActual.put(source.getFragmentId(), actualId);
            actualToLogical.put(actualId, source.getFragmentId());
            MemoryFragment fragment = MemoryFragment.builder()
                    .id(actualId)
                    .namespace(actualNamespace)
                    .content(source.getContent())
                    .tokenCount(tokenCounter.countTokens(source.getContent()))
                    .importance(source.getImportance() == null ? defaultImportance(source) : source.getImportance())
                    .tags(mergeTags(evalCase.getTags(), source.getTags()))
                    .reasoningChainId(source.getReasoningChainId())
                    .build();
            if (source.getPinTtlMillis() != null) {
                fragment.pinForMillis(source.getPinTtlMillis());
            }
            hmc.storeFragment(fragment);
        }
    }

    private LearningMemoryEvalReport.RecallObservation recall(
            LearningMemoryEvalCase evalCase,
            String actualNamespace,
            MemoryScenario scenario,
            String query,
            String phase,
            Set<String> relevantIds,
            Map<String, String> actualToLogical) {
        RecallResult result = hmc.recall(RecallQuery.builder()
                .query(query)
                .namespace(actualNamespace)
                .topK(effectiveTopK(evalCase))
                .tokenBudget(effectiveTokenBudget(evalCase))
                .scenario(scenario)
                .tags(recallTags(evalCase))
                .build());
        List<RecallResult.ScoredFragment> fragments = result == null || result.getFragments() == null
                ? List.of()
                : result.getFragments();
        List<String> actualIds = fragments.stream()
                .map(RecallResult.ScoredFragment::getFragment)
                .map(MemoryFragment::getId)
                .toList();
        List<String> logicalIds = actualIds.stream()
                .map(id -> actualToLogical.getOrDefault(id, id))
                .toList();
        List<String> tiers = fragments.stream()
                .map(RecallResult.ScoredFragment::getTier)
                .toList();
        int relevantHitCount = (int) logicalIds.stream().filter(relevantIds::contains).count();
        boolean allRelevantHit = !relevantIds.isEmpty() && logicalIds.containsAll(relevantIds);
        return LearningMemoryEvalReport.RecallObservation.builder()
                .phase(phase)
                .query(query)
                .recallSessionId(result == null ? null : result.getRecallSessionId())
                .activeProfileName(result == null ? null : result.getActiveProfileName())
                .shadowProfileName(result == null ? null : result.getShadowProfileName())
                .returnedFragmentIds(logicalIds)
                .returnedActualFragmentIds(actualIds)
                .recalledFromTiers(tiers)
                .allRelevantHit(allRelevantHit)
                .relevantHitCount(relevantHitCount)
                .relevantCount(relevantIds.size())
                .selectionPrecision(logicalIds.isEmpty() ? 0.0d : relevantHitCount / (double) logicalIds.size())
                .selectionCoverage(relevantIds.isEmpty() ? 0.0d : relevantHitCount / (double) relevantIds.size())
                .medianRelevantRank(medianRelevantRank(logicalIds, relevantIds))
                .ndcg(ndcg(logicalIds, relevantIds))
                .build();
    }

    private void submitFeedback(
            String recallSessionId,
            List<String> feedbackLogicalIds,
            Map<String, String> logicalToActual,
            LearningMemoryEvalCase evalCase) {
        if (isBlank(recallSessionId)) {
            throw new IllegalStateException("Recall did not return a session id for scenario " + evalCase.getScenarioId());
        }
        List<String> actualFeedbackIds = feedbackLogicalIds.stream()
                .map(logicalId -> {
                    String actualId = logicalToActual.get(logicalId);
                    if (actualId == null) {
                        throw new IllegalArgumentException("Feedback fragment id not found in scenario "
                                + evalCase.getScenarioId() + ": " + logicalId);
                    }
                    return actualId;
                })
                .toList();
        hmc.recordFeedback(MemoryFeedbackRequest.builder()
                .recallSessionId(recallSessionId)
                .usedFragmentIds(actualFeedbackIds)
                .answerAccepted(evalCase.getFeedback() == null || evalCase.getFeedback().isAnswerAccepted())
                .build());
    }

    private LearningMemoryEvalReport.LearningAggregate buildAggregate(
            List<LearningMemoryEvalReport.ScenarioResult> scenarios,
            LearningMemoryEvalReport.LearningProfileSnapshot before,
            LearningMemoryEvalReport.LearningProfileSnapshot after) {
        List<LearningMemoryEvalReport.RecallObservation> beforeObservations = scenarios.stream()
                .flatMap(scenario -> safeList(scenario.getObservations()).stream())
                .filter(observation -> "before-calibration".equals(observation.getPhase()))
                .toList();
        List<LearningMemoryEvalReport.RecallObservation> probeObservations = scenarios.stream()
                .flatMap(scenario -> safeList(scenario.getObservations()).stream())
                .filter(observation -> "probe".equals(observation.getPhase()))
                .toList();
        long submittedFeedback = scenarios.stream()
                .mapToLong(LearningMemoryEvalReport.ScenarioResult::getFeedbackSubmitted)
                .sum();
        long sampleDelta = Math.max(0L, nullToZero(after.getShadowSampleCount()) - nullToZero(before.getShadowSampleCount()));
        return LearningMemoryEvalReport.LearningAggregate.builder()
                .sampleCountBefore(nullToZero(before.getShadowSampleCount()))
                .sampleCountAfter(nullToZero(after.getShadowSampleCount()))
                .feedbackSampleCount(Math.max(submittedFeedback, sampleDelta))
                .activeUpdateCountBefore(nullToZero(before.getActiveUpdateCount()))
                .activeUpdateCountAfter(nullToZero(after.getActiveUpdateCount()))
                .pendingRecallSessions(after.getPendingRecallSessions() == null ? 0 : after.getPendingRecallSessions())
                .activeAverageNdcgBefore(nullToZero(before.getActiveAverageNdcg()))
                .activeAverageNdcgAfter(nullToZero(after.getActiveAverageNdcg()))
                .shadowAverageNdcgAfter(nullToZero(after.getShadowAverageNdcg()))
                .baselineAverageNdcgAfter(nullToZero(after.getBaselineAverageNdcg()))
                .activeEvictionUtilityAfter(nullToZero(after.getActiveEvictionUtility()))
                .shadowEvictionUtilityAfter(nullToZero(after.getShadowEvictionUtility()))
                .baselineEvictionUtilityAfter(nullToZero(after.getBaselineEvictionUtility()))
                .shadowRelativeLiftAfter(nullToZero(after.getShadowRelativeLift()))
                .baselineRelativeLiftAfter(nullToZero(after.getBaselineRelativeLift()))
                .shadowWinRateAfter(nullToZero(after.getShadowWinRate()))
                .baselineWinRateAfter(nullToZero(after.getBaselineWinRate()))
                .activeSelectionPrecisionAfter(averageMetric(probeObservations,
                        LearningMemoryEvalReport.RecallObservation::getSelectionPrecision))
                .activeSelectionCoverageAfter(averageMetric(probeObservations,
                        LearningMemoryEvalReport.RecallObservation::getSelectionCoverage))
                .medianRelevantRankBefore(medianMetric(beforeObservations,
                        LearningMemoryEvalReport.RecallObservation::getMedianRelevantRank))
                .medianRelevantRankAfter(medianMetric(probeObservations,
                        LearningMemoryEvalReport.RecallObservation::getMedianRelevantRank))
                .firstCalibrationAverageNdcg(averageMetric(beforeObservations,
                        LearningMemoryEvalReport.RecallObservation::getNdcg))
                .probeAverageNdcg(averageMetric(probeObservations,
                        LearningMemoryEvalReport.RecallObservation::getNdcg))
                .rankImprovedScenarioCount((int) scenarios.stream()
                        .filter(LearningMemoryEvalReport.ScenarioResult::isRankImproved)
                        .count())
                .ndcgImprovedScenarioCount((int) scenarios.stream()
                        .filter(LearningMemoryEvalReport.ScenarioResult::isNdcgImproved)
                        .count())
                .probeAllRelevantHitRate(rate(probeObservations,
                        LearningMemoryEvalReport.RecallObservation::isAllRelevantHit))
                .beforeSnapshot(before)
                .afterSnapshot(after)
                .build();
    }

    private LearningMemoryEvalReport.LearningProfileSnapshot summarizeSnapshot(
            AdaptiveWeightLearner.LearningSnapshot snapshot) {
        if (snapshot == null) {
            return LearningMemoryEvalReport.LearningProfileSnapshot.builder().build();
        }
        AdaptiveWeightProfile active = snapshot.active();
        AdaptiveWeightProfile shadowProfile = snapshot.shadow();
        ShadowEvaluationTracker.ShadowEvaluationSnapshot shadow = snapshot.shadowEvaluation();
        return LearningMemoryEvalReport.LearningProfileSnapshot.builder()
                .activeProfileName(active == null ? null : active.getProfileName())
                .activeAlpha(active == null ? null : active.getAlpha())
                .activeBeta(active == null ? null : active.getBeta())
                .activeGamma(active == null ? null : active.getGamma())
                .activeUpdateCount(active == null ? null : active.getUpdateCount())
                .shadowProfileName(shadowProfile == null ? null : shadowProfile.getProfileName())
                .shadowAlpha(shadowProfile == null ? null : shadowProfile.getAlpha())
                .shadowBeta(shadowProfile == null ? null : shadowProfile.getBeta())
                .shadowGamma(shadowProfile == null ? null : shadowProfile.getGamma())
                .shadowUpdateCount(shadowProfile == null ? null : shadowProfile.getUpdateCount())
                .shadowSampleCount(shadow == null ? null : shadow.sampleCount())
                .pendingRecallSessions(snapshot.pendingRecallSessions())
                .activeAverageNdcg(shadow == null ? null : shadow.activeAverageNdcg())
                .shadowAverageNdcg(shadow == null ? null : shadow.shadowAverageNdcg())
                .baselineAverageNdcg(shadow == null ? null : shadow.baselineAverageNdcg())
                .activeEvictionUtility(shadow == null ? null : shadow.activeEvictionUtility())
                .shadowEvictionUtility(shadow == null ? null : shadow.shadowEvictionUtility())
                .baselineEvictionUtility(shadow == null ? null : shadow.baselineEvictionUtility())
                .shadowRelativeLift(shadow == null ? null : shadow.relativeLift())
                .baselineRelativeLift(shadow == null ? null : shadow.baselineRelativeLift())
                .shadowWinRate(shadow == null ? null : shadow.shadowWinRate())
                .baselineWinRate(shadow == null ? null : shadow.baselineWinRate())
                .build();
    }

    private Set<String> relevantFragmentIds(LearningMemoryEvalCase evalCase) {
        Set<String> ids = new LinkedHashSet<>();
        for (LearningMemoryEvalCase.LearningMemoryFragment fragment : safeList(evalCase.getFragments())) {
            if (fragment.isRelevant()) {
                ids.add(fragment.getFragmentId());
            }
        }
        if (ids.isEmpty()
                && evalCase.getFeedback() != null
                && evalCase.getFeedback().getUsedFragmentIds() != null) {
            ids.addAll(evalCase.getFeedback().getUsedFragmentIds());
        }
        return ids;
    }

    private List<String> feedbackLogicalIds(LearningMemoryEvalCase evalCase, Set<String> relevantIds) {
        if (evalCase.getFeedback() != null
                && evalCase.getFeedback().getUsedFragmentIds() != null
                && !evalCase.getFeedback().getUsedFragmentIds().isEmpty()) {
            return List.copyOf(evalCase.getFeedback().getUsedFragmentIds());
        }
        return List.copyOf(relevantIds);
    }

    private List<String> recallTags(LearningMemoryEvalCase evalCase) {
        return mergeTags(evalCase.getTags(), List.of());
    }

    private List<String> mergeTags(List<String> caseTags, List<String> fragmentTags) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(LEARNING_EVAL_TAG);
        appendTags(tags, caseTags);
        appendTags(tags, fragmentTags);
        return List.copyOf(tags);
    }

    private void appendTags(Set<String> tags, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                tags.add(value.trim());
            }
        }
    }

    private double defaultImportance(LearningMemoryEvalCase.LearningMemoryFragment fragment) {
        return fragment.isRelevant() ? 0.95d : 0.25d;
    }

    private MemoryScenario firstScenario(List<LearningMemoryEvalCase> cases) {
        return cases.stream()
                .map(this::effectiveScenario)
                .findFirst()
                .orElse(properties.getDefaultMemoryScenario());
    }

    private MemoryScenario effectiveScenario(LearningMemoryEvalCase evalCase) {
        return evalCase.getMemoryScenario() == null
                ? properties.getDefaultMemoryScenario()
                : evalCase.getMemoryScenario();
    }

    private int effectiveTopK(LearningMemoryEvalCase evalCase) {
        return Math.max(1, evalCase.getTopK() == null ? properties.getDefaultTopK() : evalCase.getTopK());
    }

    private int effectiveTokenBudget(LearningMemoryEvalCase evalCase) {
        return Math.max(1, evalCase.getTokenBudget() == null
                ? properties.getDefaultTokenBudget()
                : evalCase.getTokenBudget());
    }

    private String scopedNamespace(LearningMemoryEvalCase evalCase, String runId) {
        return "%s-%s-%s".formatted(evalCase.getNamespace(), evalCase.getScenarioId(), runId);
    }

    private String scopedFragmentId(String scenarioId, String fragmentId, String runId) {
        return "%s::%s::%s".formatted(scenarioId, fragmentId, runId);
    }

    private double medianRelevantRank(List<String> rankedIds, Set<String> relevantIds) {
        if (relevantIds == null || relevantIds.isEmpty()) {
            return 0.0d;
        }
        List<Double> ranks = new ArrayList<>();
        int missingRank = rankedIds == null ? 1 : rankedIds.size() + 1;
        for (String relevantId : relevantIds) {
            int index = rankedIds == null ? -1 : rankedIds.indexOf(relevantId);
            ranks.add(index < 0 ? (double) missingRank : (double) index + 1.0d);
        }
        return median(ranks);
    }

    private double ndcg(List<String> rankedIds, Set<String> relevantIds) {
        if (rankedIds == null || rankedIds.isEmpty() || relevantIds == null || relevantIds.isEmpty()) {
            return 0.0d;
        }
        double dcg = 0.0d;
        for (int index = 0; index < rankedIds.size(); index++) {
            if (relevantIds.contains(rankedIds.get(index))) {
                dcg += 1.0d / (Math.log(index + 2.0d) / Math.log(2.0d));
            }
        }
        int idealHits = Math.min(rankedIds.size(), relevantIds.size());
        double idcg = 0.0d;
        for (int index = 0; index < idealHits; index++) {
            idcg += 1.0d / (Math.log(index + 2.0d) / Math.log(2.0d));
        }
        return idcg == 0.0d ? 0.0d : dcg / idcg;
    }

    private double medianMetric(
            List<LearningMemoryEvalReport.RecallObservation> observations,
            java.util.function.ToDoubleFunction<LearningMemoryEvalReport.RecallObservation> extractor) {
        return median(safeList(observations).stream().mapToDouble(extractor).boxed().toList());
    }

    private double averageMetric(
            List<LearningMemoryEvalReport.RecallObservation> observations,
            java.util.function.ToDoubleFunction<LearningMemoryEvalReport.RecallObservation> extractor) {
        return safeList(observations).stream().mapToDouble(extractor).average().orElse(0.0d);
    }

    private double rate(
            List<LearningMemoryEvalReport.RecallObservation> observations,
            java.util.function.Predicate<LearningMemoryEvalReport.RecallObservation> predicate) {
        List<LearningMemoryEvalReport.RecallObservation> items = safeList(observations);
        if (items.isEmpty()) {
            return 0.0d;
        }
        return items.stream().filter(predicate).count() / (double) items.size();
    }

    private double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int midpoint = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(midpoint);
        }
        return (sorted.get(midpoint - 1) + sorted.get(midpoint)) / 2.0d;
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private double nullToZero(Double value) {
        return value == null ? 0.0d : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void validateCase(LearningMemoryEvalCase evalCase) {
        if (evalCase == null) {
            throw new IllegalArgumentException("Learning eval scenario must not be null");
        }
        if (isBlank(evalCase.getScenarioId())) {
            throw new IllegalArgumentException("Learning eval scenario must define scenarioId");
        }
        if (isBlank(evalCase.getNamespace())) {
            throw new IllegalArgumentException("Learning eval scenario must define namespace");
        }
        if (safeList(evalCase.getFragments()).isEmpty()) {
            throw new IllegalArgumentException("Learning eval scenario must define fragments: " + evalCase.getScenarioId());
        }
        if (safeList(evalCase.getCalibrationQueries()).isEmpty()) {
            throw new IllegalArgumentException("Learning eval scenario must define calibrationQueries: " + evalCase.getScenarioId());
        }
        if (safeList(evalCase.getProbeQueries()).isEmpty()) {
            throw new IllegalArgumentException("Learning eval scenario must define probeQueries: " + evalCase.getScenarioId());
        }
        Set<String> ids = new HashSet<>();
        for (LearningMemoryEvalCase.LearningMemoryFragment fragment : safeList(evalCase.getFragments())) {
            if (fragment == null || isBlank(fragment.getFragmentId()) || isBlank(fragment.getContent())) {
                throw new IllegalArgumentException("Learning eval fragment must define fragmentId and content: "
                        + evalCase.getScenarioId());
            }
            if (!ids.add(fragment.getFragmentId())) {
                throw new IllegalArgumentException("Duplicate learning eval fragment id in scenario "
                        + evalCase.getScenarioId() + ": " + fragment.getFragmentId());
            }
        }
        Set<String> relevantIds = relevantFragmentIds(evalCase);
        if (relevantIds.isEmpty()) {
            throw new IllegalArgumentException("Learning eval scenario must define relevant fragments: "
                    + evalCase.getScenarioId());
        }
        for (String feedbackId : feedbackLogicalIds(evalCase, relevantIds)) {
            if (!ids.contains(feedbackId)) {
                throw new IllegalArgumentException("Feedback id does not match a fragment in scenario "
                        + evalCase.getScenarioId() + ": " + feedbackId);
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class ScenarioExecution {
        private final LearningMemoryEvalCase evalCase;
        private final MemoryScenario scenario;
        private final String actualNamespace;
        private final String runId;
        private final Map<String, String> logicalToActual;
        private final Map<String, String> actualToLogical;
        private final Set<String> relevantIds;
        private final List<String> feedbackIds;
        private final List<LearningMemoryEvalReport.RecallObservation> observations;
        private LearningMemoryEvalReport.LearningProfileSnapshot beforeSnapshot =
                LearningMemoryEvalReport.LearningProfileSnapshot.builder().build();
        private int feedbackSubmitted;

        private ScenarioExecution(
                LearningMemoryEvalCase evalCase,
                MemoryScenario scenario,
                String actualNamespace,
                String runId,
                Map<String, String> logicalToActual,
                Map<String, String> actualToLogical,
                Set<String> relevantIds,
                List<String> feedbackIds,
                List<LearningMemoryEvalReport.RecallObservation> observations) {
            this.evalCase = evalCase;
            this.scenario = scenario;
            this.actualNamespace = actualNamespace;
            this.runId = runId;
            this.logicalToActual = logicalToActual;
            this.actualToLogical = actualToLogical;
            this.relevantIds = relevantIds;
            this.feedbackIds = feedbackIds;
            this.observations = observations;
        }

        private LearningMemoryEvalCase evalCase() {
            return evalCase;
        }

        private MemoryScenario scenario() {
            return scenario;
        }

        private String actualNamespace() {
            return actualNamespace;
        }

        private String runId() {
            return runId;
        }

        private Map<String, String> logicalToActual() {
            return logicalToActual;
        }

        private Map<String, String> actualToLogical() {
            return actualToLogical;
        }

        private Set<String> relevantIds() {
            return relevantIds;
        }

        private List<String> feedbackIds() {
            return feedbackIds;
        }

        private List<LearningMemoryEvalReport.RecallObservation> observations() {
            return observations;
        }
    }
}
