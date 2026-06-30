package com.vortex.app.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeRecoveryBenchmarkExecutionService {

    private final RuntimeRecoveryBenchmarkRunner runner;
    private final RuntimeRecoveryBenchmarkReportWriter reportWriter;
    private final LlmMemoryEvalProperties properties;

    public RuntimeRecoveryBenchmarkReport executeConfiguredRun() {
        log.info("Starting runtime recovery benchmark");
        RuntimeRecoveryBenchmarkReport report = runner.runConfiguredBenchmark();
        if (properties.isWriteReport()) {
            RuntimeRecoveryBenchmarkReportWriter.WrittenReport writtenReport = reportWriter.write(report);
            log.info("Runtime recovery benchmark reports written json={} markdown={}",
                    writtenReport.jsonPath(), writtenReport.markdownPath());
        }
        log.info(
                "Runtime recovery benchmark completed successRate={} passed={}/{} averageLatencyMs={}",
                report.getSuccessRate(),
                report.getPassedCases(),
                report.getTotalCases(),
                report.getAverageLatencyMs());
        if (report.getFailedCases() > 0) {
            throw new IllegalStateException("Runtime recovery benchmark failed cases="
                    + report.getFailedCases() + "/" + report.getTotalCases());
        }
        return report;
    }
}

