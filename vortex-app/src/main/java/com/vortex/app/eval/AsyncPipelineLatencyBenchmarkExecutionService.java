package com.vortex.app.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPipelineLatencyBenchmarkExecutionService {

    private static final String ASYNC_MODE = "ASYNC_PIPELINE";

    private final AsyncPipelineLatencyBenchmarkRunner runner;
    private final AsyncPipelineLatencyBenchmarkReportWriter reportWriter;
    private final LlmMemoryEvalProperties properties;

    public AsyncPipelineLatencyBenchmarkReport executeConfiguredRun() {
        log.info("Starting async pipeline latency benchmark fragments={} warmup={}",
                properties.getAsyncPipelineBenchmarkFragments(),
                properties.getAsyncPipelineBenchmarkWarmupFragments());
        AsyncPipelineLatencyBenchmarkReport report = runner.runConfiguredBenchmark();
        if (properties.isWriteReport()) {
            AsyncPipelineLatencyBenchmarkReportWriter.WrittenReport writtenReport = reportWriter.write(report);
            log.info("Async pipeline latency benchmark reports written json={} markdown={}",
                    writtenReport.jsonPath(), writtenReport.markdownPath());
        }
        logSummary(report);
        AsyncPipelineLatencyBenchmarkReport.ModeSummary asyncSummary =
                report.getModeSummaries() == null ? null : report.getModeSummaries().get(ASYNC_MODE);
        int totalErrors = report.getModeSummaries() == null ? 0 : report.getModeSummaries().values().stream()
                .mapToInt(AsyncPipelineLatencyBenchmarkReport.ModeSummary::getErrors)
                .sum();
        if (totalErrors > 0 || asyncSummary == null || asyncSummary.getPersistenceSuccessRate() < 1.0d) {
            throw new IllegalStateException("Async pipeline latency benchmark had errors="
                    + totalErrors + " asyncPersistenceSuccessRate="
                    + (asyncSummary == null ? 0.0d : asyncSummary.getPersistenceSuccessRate()));
        }
        return report;
    }

    private void logSummary(AsyncPipelineLatencyBenchmarkReport report) {
        log.info(
                "Async pipeline latency benchmark completed syncAvgMs={} asyncAvgMs={} reduction={} persistenceSuccessRate={}",
                report.getSyncAverageMainPathLatencyMs(),
                report.getAsyncAverageMainPathLatencyMs(),
                report.getRelativeMainPathLatencyReduction(),
                report.getPersistenceSuccessRate());
        for (Map.Entry<String, AsyncPipelineLatencyBenchmarkReport.ModeSummary> entry
                : report.getModeSummaries().entrySet()) {
            AsyncPipelineLatencyBenchmarkReport.ModeSummary summary = entry.getValue();
            log.info(
                    "Async pipeline latency benchmark mode={} mainAvgMs={} mainP95Ms={} readinessP95Ms={} persistenceSuccessRate={} errors={} total={}",
                    entry.getKey(),
                    summary.getMainPathLatencyAverageMs(),
                    summary.getMainPathLatencyP95Ms(),
                    summary.getReadinessLatencyP95Ms(),
                    summary.getPersistenceSuccessRate(),
                    summary.getErrors(),
                    summary.getTotal());
        }
    }
}
