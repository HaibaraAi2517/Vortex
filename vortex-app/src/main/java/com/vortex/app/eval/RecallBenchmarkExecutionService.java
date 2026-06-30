package com.vortex.app.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecallBenchmarkExecutionService {

    private final RecallBenchmarkRunner runner;
    private final RecallBenchmarkReportWriter reportWriter;
    private final LlmMemoryEvalProperties properties;

    public RecallBenchmarkReport executeConfiguredRun() {
        log.info("Starting recall benchmark dataset={} topK={} tokenBudget={}",
                properties.getDatasetLocation(),
                properties.getRecallTopK(),
                properties.getRecallTokenBudget());
        RecallBenchmarkReport report = runner.runConfiguredBenchmark();
        if (properties.isWriteReport()) {
            RecallBenchmarkReportWriter.WrittenReport writtenReport = reportWriter.write(report);
            log.info("Recall benchmark reports written json={} markdown={}",
                    writtenReport.jsonPath(), writtenReport.markdownPath());
        }
        logSummary(report);
        return report;
    }

    private void logSummary(RecallBenchmarkReport report) {
        log.info("Recall benchmark completed totalCases={} totalRuns={}",
                report.getTotalCases(), report.getTotalRuns());
        for (Map.Entry<String, RecallBenchmarkReport.ModeSummary> entry : report.getModeSummaries().entrySet()) {
            RecallBenchmarkReport.ModeSummary summary = entry.getValue();
            log.info(
                    "Recall benchmark mode={} recallAtK={} caseHitRate={} ndcg={} liftVsVectorOnly={} errors={} total={}",
                    entry.getKey(),
                    summary.getRecallAtK(),
                    summary.getCaseHitRate(),
                    summary.getNdcg(),
                    summary.getRecallAtKLiftVsVectorOnly(),
                    summary.getErrors(),
                    summary.getTotal());
        }
    }
}
