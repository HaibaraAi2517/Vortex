package com.vortex.app.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningMemoryEvalExecutionService {

    private final LearningMemoryEvalRunner runner;
    private final LearningMemoryEvalReportWriter reportWriter;
    private final LearningMemoryEvalProperties properties;

    public LearningMemoryEvalReport executeConfiguredRun() {
        log.info("Starting learning memory eval profile={} dataset={}",
                properties.getProfileId(), properties.getDatasetLocation());
        LearningMemoryEvalReport report = runner.runConfiguredProfile();
        if (properties.isWriteReport()) {
            LearningMemoryEvalReportWriter.WrittenReport writtenReport = reportWriter.write(report);
            log.info("Learning memory eval reports written json={} markdown={}",
                    writtenReport.jsonPath(), writtenReport.markdownPath());
        }
        log.info(
                "Learning memory eval completed profile={} scenarios={} recalls={} feedback={} gatePassed={} probeAllRelevantHitRate={} activeUpdateDelta={}",
                report.getProfileId(),
                report.getScenarioCount(),
                report.getTotalRecallCount(),
                report.getFeedbackSubmitted(),
                report.isGatePassed(),
                report.getAggregate() == null ? null : report.getAggregate().getProbeAllRelevantHitRate(),
                report.getAggregate() == null
                        ? null
                        : report.getAggregate().getActiveUpdateCountAfter()
                        - report.getAggregate().getActiveUpdateCountBefore());
        if (!report.isGatePassed()) {
            throw new IllegalStateException("Learning memory eval gate failed: " + failedGateSummary(report));
        }
        return report;
    }

    private String failedGateSummary(LearningMemoryEvalReport report) {
        if (report.getGateChecks() == null || report.getGateChecks().isEmpty()) {
            return "<no gate checks>";
        }
        String summary = report.getGateChecks().stream()
                .filter(check -> !check.isPassed())
                .map(check -> check.getName() + " expected " + check.getExpected() + " actual " + check.getActual())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        return summary.isBlank() ? "<gatePassed=false without failed checks>" : summary;
    }
}
