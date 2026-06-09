package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class LearningMemoryEvalReportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final LearningMemoryEvalProperties properties;

    public WrittenReport write(LearningMemoryEvalReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Learning eval report must not be null");
        }
        Path outputDir = Path.of(properties.getReportOutputDir()).toAbsolutePath().normalize();
        String stamp = FILE_STAMP.format(report.getGeneratedAt());
        Path jsonPath = outputDir.resolve("learning-memory-eval-" + stamp + ".json");
        Path markdownPath = outputDir.resolve("learning-memory-eval-" + stamp + ".md");
        try {
            Files.createDirectories(outputDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), report);
            Files.writeString(
                    markdownPath,
                    toMarkdown(report),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return new WrittenReport(jsonPath, markdownPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write learning eval report to " + outputDir, e);
        }
    }

    private String toMarkdown(LearningMemoryEvalReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Learning Memory Eval Report\n\n");
        builder.append("- GeneratedAt: ").append(report.getGeneratedAt()).append('\n');
        builder.append("- RunId: ").append(nullToEmpty(report.getRunId())).append('\n');
        builder.append("- ProfileId: ").append(nullToEmpty(report.getProfileId())).append('\n');
        builder.append("- DatasetLocation: ").append(nullToEmpty(report.getDatasetLocation())).append('\n');
        builder.append("- DatasetVersion: ").append(nullToEmpty(report.getDatasetVersion())).append('\n');
        builder.append("- ScenarioCount: ").append(report.getScenarioCount()).append('\n');
        builder.append("- TotalRecallCount: ").append(report.getTotalRecallCount()).append('\n');
        builder.append("- FeedbackSubmitted: ").append(report.getFeedbackSubmitted()).append('\n');
        builder.append("- GatePassed: ").append(report.isGatePassed()).append("\n\n");
        appendAggregate(builder, report.getAggregate());
        appendGateChecks(builder, report.getGateChecks());
        appendScenarios(builder, report.getScenarios());
        appendObservations(builder, report.getScenarios());
        return builder.toString();
    }

    private void appendAggregate(StringBuilder builder, LearningMemoryEvalReport.LearningAggregate aggregate) {
        if (aggregate == null) {
            return;
        }
        builder.append("## Aggregate\n\n");
        builder.append("- SampleCount: ").append(aggregate.getSampleCountBefore())
                .append(" -> ").append(aggregate.getSampleCountAfter()).append('\n');
        builder.append("- FeedbackSampleCount: ").append(aggregate.getFeedbackSampleCount()).append('\n');
        builder.append("- ActiveUpdateCount: ").append(aggregate.getActiveUpdateCountBefore())
                .append(" -> ").append(aggregate.getActiveUpdateCountAfter()).append('\n');
        builder.append("- ActiveProfile: ").append(profileName(aggregate.getBeforeSnapshot()))
                .append(" -> ").append(profileName(aggregate.getAfterSnapshot())).append('\n');
        builder.append("- PendingRecallSessions: ").append(aggregate.getPendingRecallSessions()).append('\n');
        builder.append("- ActiveAverageNdcg: ").append(format(aggregate.getActiveAverageNdcgBefore()))
                .append(" -> ").append(format(aggregate.getActiveAverageNdcgAfter())).append('\n');
        builder.append("- ShadowAverageNdcgAfter: ").append(format(aggregate.getShadowAverageNdcgAfter())).append('\n');
        builder.append("- BaselineAverageNdcgAfter: ").append(format(aggregate.getBaselineAverageNdcgAfter())).append('\n');
        builder.append("- ActiveEvictionUtilityAfter: ").append(format(aggregate.getActiveEvictionUtilityAfter())).append('\n');
        builder.append("- ShadowEvictionUtilityAfter: ").append(format(aggregate.getShadowEvictionUtilityAfter())).append('\n');
        builder.append("- BaselineEvictionUtilityAfter: ").append(format(aggregate.getBaselineEvictionUtilityAfter())).append('\n');
        builder.append("- ShadowRelativeLiftAfter: ").append(format(aggregate.getShadowRelativeLiftAfter())).append('\n');
        builder.append("- BaselineRelativeLiftAfter: ").append(format(aggregate.getBaselineRelativeLiftAfter())).append('\n');
        builder.append("- ProbeAllRelevantHitRate: ").append(format(aggregate.getProbeAllRelevantHitRate())).append('\n');
        builder.append("- ActiveSelectionPrecisionAfter: ").append(format(aggregate.getActiveSelectionPrecisionAfter())).append('\n');
        builder.append("- ActiveSelectionCoverageAfter: ").append(format(aggregate.getActiveSelectionCoverageAfter())).append('\n');
        builder.append("- ObservationAverageNdcg: ").append(format(aggregate.getFirstCalibrationAverageNdcg()))
                .append(" -> ").append(format(aggregate.getProbeAverageNdcg())).append('\n');
        builder.append("- RankImprovedScenarioCount: ").append(aggregate.getRankImprovedScenarioCount()).append('\n');
        builder.append("- NdcgImprovedScenarioCount: ").append(aggregate.getNdcgImprovedScenarioCount()).append('\n');
        builder.append("- MedianRelevantRank: ").append(format(aggregate.getMedianRelevantRankBefore()))
                .append(" -> ").append(format(aggregate.getMedianRelevantRankAfter())).append("\n\n");
    }

    private void appendGateChecks(StringBuilder builder, List<LearningMemoryEvalReport.GateCheck> checks) {
        builder.append("## Gate Checks\n\n");
        builder.append("| Name | Passed | Expected | Actual | Details |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        safeList(checks).forEach(check -> builder.append("| ")
                .append(nullToEmpty(check.getName())).append(" | ")
                .append(check.isPassed()).append(" | ")
                .append(sanitize(check.getExpected())).append(" | ")
                .append(sanitize(check.getActual())).append(" | ")
                .append(sanitize(check.getDetails())).append(" |\n"));
        builder.append('\n');
    }

    private void appendScenarios(StringBuilder builder, List<LearningMemoryEvalReport.ScenarioResult> scenarios) {
        builder.append("## Scenarios\n\n");
        builder.append("| Scenario | Recalls | Feedback | Probe Hit Rate | Coverage After | Median Rank | Observation NDCG | Improved | Active NDCG | Samples | Updates |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | --- | --- | --- | --- | --- | --- |\n");
        safeList(scenarios).stream()
                .sorted(Comparator.comparing(LearningMemoryEvalReport.ScenarioResult::getScenarioId))
                .forEach(scenario -> builder.append("| ")
                        .append(sanitize(scenario.getScenarioId())).append(" | ")
                        .append(scenario.getRecallCount()).append(" | ")
                        .append(scenario.getFeedbackSubmitted()).append(" | ")
                        .append(format(scenario.getProbeAllRelevantHitRate())).append(" | ")
                        .append(format(scenario.getActiveSelectionCoverageAfter())).append(" | ")
                        .append(format(scenario.getBeforeMedianRelevantRank()))
                        .append(" -> ").append(format(scenario.getAfterMedianRelevantRank())).append(" | ")
                        .append(format(scenario.getFirstCalibrationNdcg()))
                        .append(" -> ").append(format(scenario.getProbeAverageNdcg())).append(" | ")
                        .append("rank=").append(scenario.isRankImproved())
                        .append(", ndcg=").append(scenario.isNdcgImproved()).append(" | ")
                        .append(format(scenario.getActiveAverageNdcgBefore()))
                        .append(" -> ").append(format(scenario.getActiveAverageNdcgAfter())).append(" | ")
                        .append(scenario.getSampleCountBefore()).append(" -> ")
                        .append(scenario.getSampleCountAfter()).append(" | ")
                        .append(scenario.getActiveUpdateCountBefore()).append(" -> ")
                        .append(scenario.getActiveUpdateCountAfter()).append(" |\n"));
        builder.append('\n');
    }

    private void appendObservations(StringBuilder builder, List<LearningMemoryEvalReport.ScenarioResult> scenarios) {
        builder.append("## Recall Observations\n\n");
        builder.append("| Scenario | Phase | Active Profile | All Relevant | Precision | Coverage | Median Rank | NDCG | Returned |\n");
        builder.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- |\n");
        safeList(scenarios).stream()
                .sorted(Comparator.comparing(LearningMemoryEvalReport.ScenarioResult::getScenarioId))
                .forEach(scenario -> safeList(scenario.getObservations()).forEach(observation -> builder.append("| ")
                        .append(sanitize(scenario.getScenarioId())).append(" | ")
                        .append(sanitize(observation.getPhase())).append(" | ")
                        .append(sanitize(observation.getActiveProfileName())).append(" | ")
                        .append(observation.isAllRelevantHit()).append(" | ")
                        .append(format(observation.getSelectionPrecision())).append(" | ")
                        .append(format(observation.getSelectionCoverage())).append(" | ")
                        .append(format(observation.getMedianRelevantRank())).append(" | ")
                        .append(format(observation.getNdcg())).append(" | ")
                        .append(sanitize(String.join(",", safeList(observation.getReturnedFragmentIds()))))
                        .append(" |\n")));
        builder.append('\n');
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String profileName(LearningMemoryEvalReport.LearningProfileSnapshot snapshot) {
        return snapshot == null || snapshot.getActiveProfileName() == null ? "" : snapshot.getActiveProfileName();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record WrittenReport(Path jsonPath, Path markdownPath) {
    }
}
