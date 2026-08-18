package com.vortex.app.eval;

import com.vortex.kernel.hmc.FragmentPersistenceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdmissionReclaimBenchmarkExecutionService {

    private final AdmissionReclaimBenchmarkRunner runner;
    private final AdmissionReclaimBenchmarkReportWriter reportWriter;
    private final FragmentPersistenceManager persistenceManager;
    private final LlmMemoryEvalProperties properties;

    public AdmissionReclaimBenchmarkReport executeConfiguredRun() {
        log.info(
                "Starting admission reclaim benchmark residents={} singletonParallelism={} chainSizes={}",
                properties.getReclaimBenchmarkResidentFragments(),
                properties.getReclaimBenchmarkSingletonParallelismLevels(),
                properties.getReclaimBenchmarkReasoningChainSizes());
        AdmissionReclaimBenchmarkReport report = runner.runConfiguredBenchmark();
        boolean persistenceDrained =
                persistenceManager.awaitQuiescence(properties.getReclaimBenchmarkTimeout());
        report.setPersistenceDrained(persistenceDrained);
        if (properties.isWriteReport()) {
            AdmissionReclaimBenchmarkReportWriter.WrittenReport written = reportWriter.write(report);
            log.info(
                    "Admission reclaim benchmark reports written json={} markdown={}",
                    written.jsonPath(),
                    written.markdownPath());
        }
        for (AdmissionReclaimBenchmarkReport.ScenarioResult result : report.getResults()) {
            log.info(
                    "Admission reclaim benchmark scenario={} group={} threads={} admitted={}/{} "
                            + "evicted={}/{} throughput={} p95Ms={} snapshotLockAvgMs={} "
                            + "snapshotFreezeAvgMs={} gateWaits={} gateWaitAvgMs={} "
                            + "planningAvgMs={} commitLockAvgMs={} "
                            + "conflictRate={} fallbacks={} errors={}",
                    result.getScenario(),
                    result.getVictimGroupSize(),
                    result.getParallelism(),
                    result.getAdmitted(),
                    result.getAttempted(),
                    result.getActualEvictedFragments(),
                    result.getExpectedEvictedFragments(),
                    result.getThroughputPerSecond(),
                    result.getLatencyP95Ms(),
                    result.getDetailedSnapshotLockHoldAverageMs(),
                    result.getDetailedSnapshotFreezeAverageMs(),
                    result.getPlanningGateWaitCount(),
                    result.getPlanningGateWaitAverageMs(),
                    result.getPlanningAverageMs(),
                    result.getCommitLockHoldAverageMs(),
                    result.getOptimisticConflictRate(),
                    result.getFallbacks(),
                    result.getErrors());
        }
        int errors = report.getResults().stream()
                .mapToInt(AdmissionReclaimBenchmarkReport.ScenarioResult::getErrors)
                .sum();
        if (errors > 0) {
            throw new IllegalStateException("Admission reclaim benchmark had errors=" + errors);
        }
        if (!persistenceDrained) {
            throw new IllegalStateException(
                    "Admission reclaim benchmark persistence did not drain before timeout");
        }
        return report;
    }
}
