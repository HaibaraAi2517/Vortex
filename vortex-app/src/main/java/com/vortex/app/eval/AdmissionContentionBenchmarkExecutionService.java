package com.vortex.app.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdmissionContentionBenchmarkExecutionService {

    private final AdmissionContentionBenchmarkRunner runner;
    private final AdmissionContentionBenchmarkReportWriter reportWriter;
    private final LlmMemoryEvalProperties properties;

    public AdmissionContentionBenchmarkReport executeConfiguredRun() {
        log.info(
                "Starting admission contention benchmark parallelism={} operationsPerThread={} warmupPerThread={}",
                properties.getAdmissionBenchmarkParallelismLevels(),
                properties.getAdmissionBenchmarkOperationsPerThread(),
                properties.getAdmissionBenchmarkWarmupOperationsPerThread());
        AdmissionContentionBenchmarkReport report = runner.runConfiguredBenchmark();
        if (properties.isWriteReport()) {
            AdmissionContentionBenchmarkReportWriter.WrittenReport written = reportWriter.write(report);
            log.info("Admission contention benchmark reports written json={} markdown={}",
                    written.jsonPath(), written.markdownPath());
        }
        for (AdmissionContentionBenchmarkReport.ParallelismResult result : report.getResults()) {
            log.info(
                    "Admission contention benchmark threads={} admitted={}/{} throughput={} p95Ms={} p99Ms={} locksPerRequest={} lockWaitAvgMs={} lockHoldAvgMs={} directCommits={} directEscalations={} conflictRate={} fallbackRate={} errors={}",
                    result.getParallelism(),
                    result.getAdmitted(),
                    result.getAttempted(),
                    result.getThroughputPerSecond(),
                    result.getLatencyP95Ms(),
                    result.getLatencyP99Ms(),
                    result.getLockAcquisitionsPerRequest(),
                    result.getLockWaitAverageMs(),
                    result.getLockHoldAverageMs(),
                    result.getDirectCommits(),
                    result.getDirectEscalations(),
                    result.getOptimisticConflictRate(),
                    result.getFallbackRate(),
                    result.getErrors());
        }
        int errors = report.getResults().stream()
                .mapToInt(AdmissionContentionBenchmarkReport.ParallelismResult::getErrors)
                .sum();
        if (errors > 0) {
            throw new IllegalStateException("Admission contention benchmark had errors=" + errors);
        }
        return report;
    }
}
