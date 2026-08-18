package com.vortex.kernel.hmc;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySloTrackerTest {

    @Test
    void snapshotIncludesRollingP95AndP99Latency() {
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        tracker.bind();

        tracker.recordStoreLatency(1_000_000L);
        tracker.recordStoreLatency(5_000_000L);
        tracker.recordStoreLatency(9_000_000L);
        tracker.recordRecallLatency(2_000_000L);
        tracker.recordRecallLatency(6_000_000L);
        tracker.recordRecallLatency(10_000_000L);

        MemorySloTracker.SloSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.storeLatencyP95Ms()).isGreaterThanOrEqualTo(5.0);
        assertThat(snapshot.storeLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.storeLatencyP95Ms());
        assertThat(snapshot.recallLatencyP95Ms()).isGreaterThanOrEqualTo(6.0);
        assertThat(snapshot.recallLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.recallLatencyP95Ms());
    }

    @Test
    void rollingPercentileMaintainsWindowWithoutResortingOnRead() {
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        tracker.bind();

        for (int i = 1; i <= 600; i++) {
            tracker.recordStoreLatency(i * 1_000_000L);
        }

        MemorySloTracker.SloSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.storeCount()).isEqualTo(600);
        assertThat(snapshot.storeLatencyP95Ms()).isGreaterThanOrEqualTo(570.0);
        assertThat(snapshot.storeLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.storeLatencyP95Ms());
        assertThat(snapshot.storeLatencyP99Ms()).isLessThanOrEqualTo(600.0);
    }

    @Test
    void concurrentBurstyLatencyRecordingKeepsTailMetricsStable() throws Exception {
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        tracker.bind();

        int threads = 8;
        int iterationsPerThread = 400;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                for (int i = 1; i <= iterationsPerThread; i++) {
                    long storeNanos = (i % 100 == 0) ? 80_000_000L : 1_000_000L;
                    long recallNanos = (i % 120 == 0) ? 120_000_000L : 2_000_000L;
                    tracker.recordStoreLatency(storeNanos);
                    tracker.recordRecallLatency(recallNanos);
                }
            });
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        MemorySloTracker.SloSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.storeCount()).isEqualTo((long) threads * iterationsPerThread);
        assertThat(snapshot.recallCount()).isEqualTo((long) threads * iterationsPerThread);
        assertThat(snapshot.storeLatencyP95Ms()).isLessThan(5.0);
        assertThat(snapshot.storeLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.storeLatencyP95Ms());
        assertThat(snapshot.storeLatencyP99Ms()).isLessThanOrEqualTo(snapshot.storeLatencyMaxMs());
        assertThat(snapshot.recallLatencyP95Ms()).isLessThan(10.0);
        assertThat(snapshot.recallLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.recallLatencyP95Ms());
        assertThat(snapshot.recallLatencyP99Ms()).isLessThanOrEqualTo(snapshot.recallLatencyMaxMs());
    }

    @Test
    void admissionMetricsExposeLockPlanningConflictAndFallbackSignals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();

        tracker.recordAdmissionPlanningGateWait(1_500_000L);
        tracker.recordAdmissionLockWait(2_000_000L);
        tracker.recordAdmissionLockHold(3_000_000L);
        tracker.recordAdmissionPlanning(4_000_000L);
        tracker.recordAdmissionDetailedSnapshotLockHold(500_000L);
        tracker.recordAdmissionDetailedSnapshotFreeze(6_000_000L);
        tracker.recordAdmissionCommitLockHold(700_000L);
        tracker.recordAdmissionRequest();
        tracker.recordAdmissionDirectAttempt();
        tracker.recordAdmissionDirectCommit();
        tracker.recordAdmissionDirectEscalation();
        tracker.recordAdmissionDirectRejection();
        tracker.recordAdmissionOptimisticAttempt();
        tracker.recordAdmissionOptimisticCommit();
        tracker.recordAdmissionOptimisticConflict();
        tracker.recordAdmissionFallback();

        assertThat(registry.get("vortex.hmc.admission.planning.gate.wait.count").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("vortex.hmc.admission.planning.gate.wait.total.ms").gauge().value())
                .isEqualTo(1.5);
        assertThat(registry.get("vortex.hmc.admission.planning.gate.wait.max.ms").gauge().value())
                .isEqualTo(1.5);
        assertThat(registry.get("vortex.hmc.admission.lock.wait.total.ms").gauge().value())
                .isEqualTo(2.0);
        assertThat(registry.get("vortex.hmc.admission.lock.hold.max.ms").gauge().value())
                .isEqualTo(3.0);
        assertThat(registry.get("vortex.hmc.admission.planning.max.ms").gauge().value())
                .isEqualTo(4.0);
        assertThat(registry.get("vortex.hmc.admission.snapshot.detailed.lock.hold.max.ms").gauge().value())
                .isEqualTo(0.5);
        assertThat(registry.get("vortex.hmc.admission.snapshot.detailed.freeze.max.ms").gauge().value())
                .isEqualTo(6.0);
        assertThat(registry.get("vortex.hmc.admission.commit.lock.hold.max.ms").gauge().value())
                .isEqualTo(0.7);
        assertThat(registry.get("vortex.hmc.admission.optimistic.conflict.count").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("vortex.hmc.admission.direct.commit.count").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("vortex.hmc.admission.direct.escalation.count").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("vortex.hmc.admission.fallback.count").gauge().value())
                .isEqualTo(1.0);

        MemorySloTracker.AdmissionSloSnapshot admission = tracker.snapshot().admission();
        assertThat(admission.requestCount()).isEqualTo(1);
        assertThat(admission.directAttemptCount()).isEqualTo(1);
        assertThat(admission.directCommitCount()).isEqualTo(1);
        assertThat(admission.directEscalationCount()).isEqualTo(1);
        assertThat(admission.directRejectionCount()).isEqualTo(1);
        assertThat(admission.optimisticAttemptCount()).isEqualTo(1);
        assertThat(admission.optimisticCommitCount()).isEqualTo(1);
        assertThat(admission.planningGateWaitCount()).isEqualTo(1);
        assertThat(admission.planningGateWaitTotalMs()).isEqualTo(1.5);
        assertThat(admission.planningGateWaitAverageMs()).isEqualTo(1.5);
        assertThat(admission.planningGateWaitMaxMs()).isEqualTo(1.5);
        assertThat(admission.lockAcquisitionCount()).isEqualTo(1);
        assertThat(admission.lockWaitAverageMs()).isEqualTo(2.0);
        assertThat(admission.lockHoldAverageMs()).isEqualTo(3.0);
        assertThat(admission.planningAverageMs()).isEqualTo(4.0);
        assertThat(admission.optimisticConflictRate()).isEqualTo(1.0);
        assertThat(admission.fallbackRate()).isEqualTo(1.0);

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(metrics.planningGateWaitCount()).isEqualTo(1);
        assertThat(metrics.planningGateWaitNanosTotal()).isEqualTo(1_500_000L);
        assertThat(metrics.planningGateWaitNanosMax()).isEqualTo(1_500_000L);
        assertThat(metrics.detailedSnapshotCount()).isEqualTo(1);
        assertThat(metrics.detailedSnapshotLockHoldNanosTotal()).isEqualTo(500_000L);
        assertThat(metrics.detailedSnapshotFreezeCount()).isEqualTo(1);
        assertThat(metrics.detailedSnapshotFreezeNanosTotal()).isEqualTo(6_000_000L);
        assertThat(metrics.commitLockCount()).isEqualTo(1);
        assertThat(metrics.commitLockHoldNanosTotal()).isEqualTo(700_000L);
    }
}
