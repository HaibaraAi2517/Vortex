package com.vortex.app.eval;

import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningMemoryEvalVerifierTest {

    private final LearningMemoryEvalProperties properties = new LearningMemoryEvalProperties();
    private final LearningMemoryEvalVerifier verifier =
            new LearningMemoryEvalVerifier(JsonMapperFactory.create(), properties);

    @Test
    void verifyShouldAcceptPassingLearningReport() {
        LearningMemoryEvalVerificationResult result = verifier.verify(
                "memory",
                passingReport(),
                properties.getProfileId());

        assertThat(result.isPassed()).isTrue();
        assertThat(result.renderHumanReadable()).contains("PASS");
    }

    @Test
    void verifyShouldRejectPendingRecallSessions() {
        LearningMemoryEvalReport report = passingReport();
        report.getAggregate().setPendingRecallSessions(1);

        LearningMemoryEvalVerificationResult result = verifier.verify(
                "memory",
                report,
                properties.getProfileId());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getDrifts())
                .extracting(LearningMemoryEvalVerificationResult.Drift::field)
                .contains("aggregate.pendingRecallSessions");
    }

    @Test
    void verifyShouldRejectMissingBeforeCalibrationPhase() {
        LearningMemoryEvalReport report = passingReport();
        report.getScenarios().forEach(scenario -> scenario.setObservations(
                scenario.getObservations().stream()
                        .filter(observation -> !"before-calibration".equals(observation.getPhase()))
                        .toList()));

        LearningMemoryEvalVerificationResult result = verifier.verify(
                "memory",
                report,
                properties.getProfileId());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getDrifts())
                .extracting(LearningMemoryEvalVerificationResult.Drift::field)
                .contains("scenarios.observationPhases");
    }

    static LearningMemoryEvalReport passingReport() {
        List<LearningMemoryEvalReport.ScenarioResult> scenarios = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(index -> LearningMemoryEvalReport.ScenarioResult.builder()
                        .scenarioId("learning-v1-test-" + index)
                        .namespace("learning-v1")
                        .actualNamespace("learning-v1-" + index)
                        .memoryScenario("CHAT")
                        .fragmentCount(6)
                        .recallCount(8)
                        .feedbackSubmitted(8)
                        .relevantFragmentIds(List.of("relevant-" + index))
                        .beforeMedianRelevantRank(2.0d)
                        .afterMedianRelevantRank(1.0d)
                        .firstCalibrationNdcg(0.6d)
                        .probeAverageNdcg(1.0d)
                        .rankImproved(true)
                        .ndcgImproved(true)
                        .probeAllRelevantHitRate(1.0d)
                        .activeSelectionPrecisionAfter(1.0d)
                        .activeSelectionCoverageAfter(1.0d)
                        .sampleCountBefore((index - 1L) * 8L)
                        .sampleCountAfter(index * 8L)
                        .activeUpdateCountBefore(0L)
                        .activeUpdateCountAfter(1L)
                        .activeAverageNdcgBefore(0.0d)
                        .activeAverageNdcgAfter(1.0d)
                        .observations(List.of(
                                LearningMemoryEvalReport.RecallObservation.builder()
                                        .phase("before-calibration")
                                        .query("q0")
                                        .recallSessionId("session-before-" + index)
                                        .returnedFragmentIds(List.of("distractor-" + index))
                                        .allRelevantHit(false)
                                        .relevantHitCount(0)
                                        .relevantCount(1)
                                        .selectionPrecision(0.0d)
                                        .selectionCoverage(0.0d)
                                        .medianRelevantRank(2.0d)
                                        .ndcg(0.0d)
                                        .build(),
                                LearningMemoryEvalReport.RecallObservation.builder()
                                        .phase("probe")
                                        .query("q")
                                        .recallSessionId("session-" + index)
                                        .returnedFragmentIds(List.of("relevant-" + index))
                                        .allRelevantHit(true)
                                        .relevantHitCount(1)
                                        .relevantCount(1)
                                        .selectionPrecision(1.0d)
                                        .selectionCoverage(1.0d)
                                        .medianRelevantRank(1.0d)
                                        .ndcg(1.0d)
                                        .build()))
                        .build())
                .toList();
        return LearningMemoryEvalReport.builder()
                .generatedAt(Instant.parse("2026-06-09T06:00:00Z"))
                .runId("test")
                .profileId("learning-v1-agent-feedback-audit")
                .datasetLocation("classpath:llm-memory-eval-set-learning-v1-agent-feedback.json")
                .datasetVersion("learning-v1-agent-feedback")
                .scenarioCount(5)
                .totalRecallCount(40)
                .feedbackSubmitted(40)
                .gatePassed(true)
                .aggregate(LearningMemoryEvalReport.LearningAggregate.builder()
                        .sampleCountBefore(0L)
                        .sampleCountAfter(40L)
                        .feedbackSampleCount(40L)
                        .activeUpdateCountBefore(0L)
                        .activeUpdateCountAfter(1L)
                        .pendingRecallSessions(0)
                        .activeAverageNdcgBefore(0.0d)
                        .activeAverageNdcgAfter(1.0d)
                        .shadowAverageNdcgAfter(1.0d)
                        .baselineAverageNdcgAfter(1.0d)
                        .activeEvictionUtilityAfter(1.0d)
                        .shadowEvictionUtilityAfter(1.0d)
                        .baselineEvictionUtilityAfter(1.0d)
                        .shadowRelativeLiftAfter(0.0d)
                        .baselineRelativeLiftAfter(0.0d)
                        .shadowWinRateAfter(0.0d)
                        .baselineWinRateAfter(0.0d)
                        .activeSelectionPrecisionAfter(1.0d)
                        .activeSelectionCoverageAfter(1.0d)
                        .medianRelevantRankBefore(2.0d)
                        .medianRelevantRankAfter(1.0d)
                        .firstCalibrationAverageNdcg(0.6d)
                        .probeAverageNdcg(1.0d)
                        .rankImprovedScenarioCount(5)
                        .ndcgImprovedScenarioCount(5)
                        .probeAllRelevantHitRate(1.0d)
                        .build())
                .gateChecks(List.of(LearningMemoryEvalReport.GateCheck.builder()
                        .name("scenarioCount")
                        .passed(true)
                        .expected(">=5")
                        .actual("5")
                        .build()))
                .scenarios(scenarios)
                .build();
    }
}
