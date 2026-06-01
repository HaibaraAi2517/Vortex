package com.vortex.app.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmMemoryEvalExecutionService {

    private final LlmMemoryEvalRunner evalRunner;
    private final LlmMemoryEvalReportWriter reportWriter;
    private final LlmMemoryEvalProperties properties;
    private final LlmMemoryEvalEnvironmentSnapshotFactory environmentSnapshotFactory;

    public LlmMemoryEvalReport executeConfiguredRun() {
        log.info(
                "Starting LLM memory eval dataset={} modes={} feedbackEnabled={} learningScenario={}",
                properties.getDatasetLocation(),
                configuredModes(),
                properties.isFeedbackEnabled(),
                properties.getLearningScenario());

        LlmMemoryEvalReport report = evalRunner.runConfiguredModes();
        report.setEnvironment(environmentSnapshotFactory.snapshot());
        if (properties.isWriteReport()) {
            LlmMemoryEvalReportWriter.WrittenReport writtenReport = reportWriter.write(report);
            log.info("LLM memory eval reports written json={} markdown={}",
                    writtenReport.jsonPath(), writtenReport.markdownPath());
        }
        logSummary(report);
        return report;
    }

    private void logSummary(LlmMemoryEvalReport report) {
        log.info("LLM memory eval completed totalCases={} totalRuns={}",
                report.getTotalCases(), report.getTotalRuns());
        for (Map.Entry<String, LlmMemoryEvalReport.ModeSummary> entry : report.getModeSummaries().entrySet()) {
            LlmMemoryEvalReport.ModeSummary summary = entry.getValue();
            log.info(
                    "LLM memory eval mode={} accuracy={} recallHitRate={} recoveredAccuracy={} recoveredL2HitRate={} feedbackSubmitted={} learningUpdateDelta={} averageLatencyMs={} correct={}/{}",
                    entry.getKey(),
                    summary.getAccuracy(),
                    summary.getRecallHitRate(),
                    summary.getRecoveredAccuracy(),
                    summary.getRecoveredL2HitRate(),
                    summary.getFeedbackSubmitted(),
                    summary.getLearningUpdateCountDelta(),
                    summary.getAverageLatencyMs(),
                    summary.getCorrect(),
                    summary.getTotal());
        }
    }

    private List<String> configuredModes() {
        return properties.getModes() == null
                ? List.of()
                : properties.getModes().stream().map(LlmMemoryEvalMode::reportName).toList();
    }
}
