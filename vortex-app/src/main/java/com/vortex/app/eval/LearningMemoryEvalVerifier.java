package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class LearningMemoryEvalVerifier {

    private final ObjectMapper objectMapper;
    private final LearningMemoryEvalProperties properties;

    public LearningMemoryEvalVerifier(ObjectMapper objectMapper, LearningMemoryEvalProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public LearningMemoryEvalVerificationResult verify(Path reportPath) {
        return verify(reportPath, properties.getProfileId());
    }

    public LearningMemoryEvalVerificationResult verify(Path reportPath, String expectedProfileId) {
        Path normalizedPath = Objects.requireNonNull(reportPath, "reportPath must not be null")
                .toAbsolutePath()
                .normalize();
        return verify(normalizedPath.toString(), readReport(normalizedPath), expectedProfileId);
    }

    public LearningMemoryEvalVerificationResult verify(
            String reportPath,
            LearningMemoryEvalReport report,
            String expectedProfileId) {
        List<LearningMemoryEvalVerificationResult.Drift> drifts = new ArrayList<>();
        LearningMemoryEvalReport.LearningAggregate aggregate = report == null ? null : report.getAggregate();
        String actualProfileId = report == null ? null : report.getProfileId();
        String actualDatasetVersion = report == null ? null : report.getDatasetVersion();

        expectEqual(drifts, "profileId", expectedProfileId, actualProfileId);
        expectTrue(drifts, "generatedAt", report != null && report.getGeneratedAt() != null, "<present>", formatValue(null));
        expectTrue(drifts, "gatePassed", report != null && report.isGatePassed(), "true",
                report == null ? "null" : Boolean.toString(report.isGatePassed()));
        expectTrue(
                drifts,
                "gateChecks",
                report != null
                        && report.getGateChecks() != null
                        && !report.getGateChecks().isEmpty()
                        && report.getGateChecks().stream().allMatch(LearningMemoryEvalReport.GateCheck::isPassed),
                "all passed",
                gateCheckSummary(report));
        if (report == null || aggregate == null) {
            drifts.add(new LearningMemoryEvalVerificationResult.Drift(
                    "aggregate",
                    "<present>",
                    "<missing>"));
        } else {
            verifyReportShape(drifts, report, aggregate);
            verifyAggregateThresholds(drifts, report, aggregate);
        }

        return LearningMemoryEvalVerificationResult.builder()
                .profileId(expectedProfileId)
                .datasetVersion(actualDatasetVersion)
                .reportPath(reportPath)
                .passed(drifts.isEmpty())
                .drifts(List.copyOf(drifts))
                .build();
    }

    private void verifyReportShape(
            List<LearningMemoryEvalVerificationResult.Drift> drifts,
            LearningMemoryEvalReport report,
            LearningMemoryEvalReport.LearningAggregate aggregate) {
        int scenarioListSize = report.getScenarios() == null ? 0 : report.getScenarios().size();
        expectEqual(drifts, "scenarioCount", scenarioListSize, report.getScenarioCount());
        expectEqual(drifts, "feedbackSubmitted", report.getTotalRecallCount(), report.getFeedbackSubmitted());
        expectEqual(drifts, "aggregate.pendingRecallSessions", 0, aggregate.getPendingRecallSessions());
        expectTrue(
                drifts,
                "scenarios.observations",
                report.getScenarios() != null
                        && report.getScenarios().stream()
                        .allMatch(scenario -> scenario.getObservations() != null
                                && !scenario.getObservations().isEmpty()),
                "all scenarios have observations",
                "missing or empty observations");
        expectTrue(
                drifts,
                "scenarios.observationPhases",
                report.getScenarios() != null
                        && report.getScenarios().stream()
                        .allMatch(scenario -> hasPhase(scenario, "before-calibration")
                                && hasPhase(scenario, "probe")),
                "each scenario has before-calibration and probe",
                phaseSummary(report));
    }

    private void verifyAggregateThresholds(
            List<LearningMemoryEvalVerificationResult.Drift> drifts,
            LearningMemoryEvalReport report,
            LearningMemoryEvalReport.LearningAggregate aggregate) {
        expectAtLeast(drifts, "scenarioCount", properties.getMinScenarioCount(), report.getScenarioCount());
        expectAtLeast(drifts, "aggregate.feedbackSampleCount",
                properties.getMinFeedbackSampleCount(), aggregate.getFeedbackSampleCount());
        expectAtLeast(drifts, "aggregate.probeAllRelevantHitRate",
                properties.getMinProbeAllRelevantHitRate(), aggregate.getProbeAllRelevantHitRate());
        expectAtLeast(drifts, "aggregate.probeAverageNdcg",
                properties.getMinProbeAverageNdcg(), aggregate.getProbeAverageNdcg());
        expectAtLeast(drifts, "aggregate.rankImprovedScenarioCount",
                properties.getMinRankImprovedScenarioCount(), aggregate.getRankImprovedScenarioCount());
        expectAtLeast(drifts, "aggregate.ndcgImprovedScenarioCount",
                properties.getMinNdcgImprovedScenarioCount(), aggregate.getNdcgImprovedScenarioCount());
        expectTrue(
                drifts,
                "aggregate.activeUpdateCount",
                aggregate.getActiveUpdateCountAfter() > aggregate.getActiveUpdateCountBefore(),
                "after > before",
                aggregate.getActiveUpdateCountBefore() + " -> " + aggregate.getActiveUpdateCountAfter());
    }

    private LearningMemoryEvalReport readReport(Path reportPath) {
        try {
            return objectMapper.readValue(reportPath.toFile(), LearningMemoryEvalReport.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read learning eval report json: " + reportPath, e);
        }
    }

    private void expectEqual(
            List<LearningMemoryEvalVerificationResult.Drift> drifts,
            String field,
            Object expected,
            Object actual) {
        if (!Objects.equals(expected, actual)) {
            drifts.add(new LearningMemoryEvalVerificationResult.Drift(
                    field,
                    formatValue(expected),
                    formatValue(actual)));
        }
    }

    private void expectTrue(
            List<LearningMemoryEvalVerificationResult.Drift> drifts,
            String field,
            boolean passed,
            String expected,
            String actual) {
        if (!passed) {
            drifts.add(new LearningMemoryEvalVerificationResult.Drift(field, expected, actual));
        }
    }

    private void expectAtLeast(
            List<LearningMemoryEvalVerificationResult.Drift> drifts,
            String field,
            long expectedMinimum,
            long actual) {
        if (actual < expectedMinimum) {
            drifts.add(new LearningMemoryEvalVerificationResult.Drift(
                    field,
                    ">=" + expectedMinimum,
                    Long.toString(actual)));
        }
    }

    private void expectAtLeast(
            List<LearningMemoryEvalVerificationResult.Drift> drifts,
            String field,
            double expectedMinimum,
            double actual) {
        if (actual + 1.0e-9 < expectedMinimum) {
            drifts.add(new LearningMemoryEvalVerificationResult.Drift(
                    field,
                    ">=" + format(expectedMinimum),
                    format(actual)));
        }
    }

    private String gateCheckSummary(LearningMemoryEvalReport report) {
        if (report == null || report.getGateChecks() == null) {
            return "null";
        }
        return report.getGateChecks().stream()
                .map(check -> check.getName() + "=" + check.isPassed())
                .reduce((left, right) -> left + ", " + right)
                .orElse("<empty>");
    }

    private boolean hasPhase(LearningMemoryEvalReport.ScenarioResult scenario, String expectedPhase) {
        return scenario != null
                && scenario.getObservations() != null
                && scenario.getObservations().stream()
                .anyMatch(observation -> expectedPhase.equals(observation.getPhase()));
    }

    private String phaseSummary(LearningMemoryEvalReport report) {
        if (report == null || report.getScenarios() == null) {
            return "null";
        }
        return report.getScenarios().stream()
                .map(scenario -> scenario.getScenarioId() + "=" + safeObservations(scenario).stream()
                        .map(LearningMemoryEvalReport.RecallObservation::getPhase)
                        .filter(Objects::nonNull)
                        .distinct()
                        .reduce((left, right) -> left + "/" + right)
                        .orElse("<empty>"))
                .reduce((left, right) -> left + ", " + right)
                .orElse("<empty>");
    }

    private List<LearningMemoryEvalReport.RecallObservation> safeObservations(
            LearningMemoryEvalReport.ScenarioResult scenario) {
        if (scenario == null || scenario.getObservations() == null) {
            return List.of();
        }
        return scenario.getObservations();
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return "\"" + stringValue + "\"";
        }
        return value.toString();
    }
}
