package com.vortex.app.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class LearningMemoryEvalGateEvaluator {

    private final LearningMemoryEvalProperties properties;

    public List<LearningMemoryEvalReport.GateCheck> evaluate(LearningMemoryEvalReport report) {
        if (report == null || report.getAggregate() == null) {
            throw new IllegalArgumentException("Learning eval report and aggregate must not be null");
        }
        LearningMemoryEvalReport.LearningAggregate aggregate = report.getAggregate();
        List<LearningMemoryEvalReport.GateCheck> checks = new ArrayList<>();
        checks.add(check(
                "scenarioCount",
                report.getScenarioCount() >= properties.getMinScenarioCount(),
                ">=" + properties.getMinScenarioCount(),
                Integer.toString(report.getScenarioCount()),
                "At least five scenario groups are required for the candidate workload."));
        checks.add(check(
                "feedbackSampleCount",
                aggregate.getFeedbackSampleCount() >= properties.getMinFeedbackSampleCount(),
                ">=" + properties.getMinFeedbackSampleCount(),
                Long.toString(aggregate.getFeedbackSampleCount()),
                "Feedback count is measured from submitted deterministic recall sessions."));
        checks.add(check(
                "pendingRecallSessions",
                aggregate.getPendingRecallSessions() == 0,
                "0",
                Integer.toString(aggregate.getPendingRecallSessions()),
                "Every calibration and probe recall must be closed by feedback."));
        checks.add(check(
                "feedbackSubmitted",
                report.getFeedbackSubmitted() == report.getTotalRecallCount(),
                "totalRecallCount",
                report.getFeedbackSubmitted() + " / " + report.getTotalRecallCount(),
                "Every deterministic recall should have one submitted feedback event."));
        checks.add(check(
                "observationPhases",
                everyScenarioHasObservationPhases(report),
                "each scenario has before-calibration and probe",
                phaseSummary(report),
                "Learning improvement must compare an initial recall against post-feedback probes."));
        checks.add(check(
                "activeUpdateCount",
                aggregate.getActiveUpdateCountAfter() > aggregate.getActiveUpdateCountBefore(),
                "after > before",
                aggregate.getActiveUpdateCountBefore() + " -> " + aggregate.getActiveUpdateCountAfter(),
                "AdaptiveWeightLearner active profile should receive at least one update."));
        checks.add(check(
                "probeAllRelevantHitRate",
                aggregate.getProbeAllRelevantHitRate() >= properties.getMinProbeAllRelevantHitRate(),
                ">=" + format(properties.getMinProbeAllRelevantHitRate()),
                format(aggregate.getProbeAllRelevantHitRate()),
                "Probe recalls should return every required relevant fragment."));
        checks.add(check(
                "probeAverageNdcg",
                aggregate.getProbeAverageNdcg() >= properties.getMinProbeAverageNdcg(),
                ">=" + format(properties.getMinProbeAverageNdcg()),
                format(aggregate.getProbeAverageNdcg()),
                "Probe ranking quality should remain high after feedback."));
        checks.add(check(
                "activeAverageNdcg",
                aggregate.getActiveAverageNdcgAfter() >= aggregate.getActiveAverageNdcgBefore(),
                "after >= before",
                format(aggregate.getActiveAverageNdcgBefore()) + " -> " + format(aggregate.getActiveAverageNdcgAfter()),
                "Active ranking quality should not regress after feedback."));
        checks.add(check(
                "observationNdcg",
                aggregate.getProbeAverageNdcg() >= aggregate.getFirstCalibrationAverageNdcg(),
                "probe >= first calibration",
                format(aggregate.getFirstCalibrationAverageNdcg()) + " -> " + format(aggregate.getProbeAverageNdcg()),
                "Observation-level probe NDCG should not regress from the first calibration recall."));
        checks.add(check(
                "medianRelevantRank",
                aggregate.getMedianRelevantRankAfter() <= aggregate.getMedianRelevantRankBefore(),
                "after <= before",
                format(aggregate.getMedianRelevantRankBefore()) + " -> " + format(aggregate.getMedianRelevantRankAfter()),
                "Relevant fragments should be no later in probe ranking than in first calibration ranking."));
        checks.add(check(
                "rankImprovedScenarioCount",
                aggregate.getRankImprovedScenarioCount() >= properties.getMinRankImprovedScenarioCount(),
                ">=" + properties.getMinRankImprovedScenarioCount(),
                Integer.toString(aggregate.getRankImprovedScenarioCount()),
                "Counts scenarios where probe median relevant rank improved versus first calibration."));
        checks.add(check(
                "ndcgImprovedScenarioCount",
                aggregate.getNdcgImprovedScenarioCount() >= properties.getMinNdcgImprovedScenarioCount(),
                ">=" + properties.getMinNdcgImprovedScenarioCount(),
                Integer.toString(aggregate.getNdcgImprovedScenarioCount()),
                "Counts scenarios where probe NDCG improved versus first calibration."));
        boolean everyScenarioHasCoverage = report.getScenarios() != null
                && report.getScenarios().stream()
                .allMatch(scenario -> scenario.getActiveSelectionCoverageAfter() > 0.0d);
        checks.add(check(
                "activeSelectionCoverage",
                everyScenarioHasCoverage,
                "all scenarios > 0.0",
                coverageSummary(report),
                "Each scenario must return at least one relevant fragment after feedback."));
        return List.copyOf(checks);
    }

    public boolean passed(List<LearningMemoryEvalReport.GateCheck> checks) {
        return checks != null && checks.stream().allMatch(LearningMemoryEvalReport.GateCheck::isPassed);
    }

    private LearningMemoryEvalReport.GateCheck check(
            String name,
            boolean passed,
            String expected,
            String actual,
            String details) {
        return LearningMemoryEvalReport.GateCheck.builder()
                .name(name)
                .passed(passed)
                .expected(expected)
                .actual(actual)
                .details(details)
                .build();
    }

    private String coverageSummary(LearningMemoryEvalReport report) {
        if (report.getScenarios() == null || report.getScenarios().isEmpty()) {
            return "";
        }
        return report.getScenarios().stream()
                .map(scenario -> scenario.getScenarioId() + "=" + format(scenario.getActiveSelectionCoverageAfter()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private boolean everyScenarioHasObservationPhases(LearningMemoryEvalReport report) {
        return report.getScenarios() != null
                && !report.getScenarios().isEmpty()
                && report.getScenarios().stream()
                .allMatch(scenario -> safePhases(scenario).contains("before-calibration")
                        && safePhases(scenario).contains("probe"));
    }

    private String phaseSummary(LearningMemoryEvalReport report) {
        if (report.getScenarios() == null || report.getScenarios().isEmpty()) {
            return "";
        }
        return report.getScenarios().stream()
                .map(scenario -> scenario.getScenarioId() + "=" + String.join("/", safePhases(scenario)))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private List<String> safePhases(LearningMemoryEvalReport.ScenarioResult scenario) {
        if (scenario.getObservations() == null || scenario.getObservations().isEmpty()) {
            return List.of();
        }
        return scenario.getObservations().stream()
                .map(LearningMemoryEvalReport.RecallObservation::getPhase)
                .filter(phase -> phase != null && !phase.isBlank())
                .distinct()
                .toList();
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
