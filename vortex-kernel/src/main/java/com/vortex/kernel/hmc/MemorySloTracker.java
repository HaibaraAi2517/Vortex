package com.vortex.kernel.hmc;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MemorySloTracker {

    private static final int LATENCY_SAMPLE_WINDOW = 512;

    private final MeterRegistry meterRegistry;
    private final AtomicLong storeCount = new AtomicLong();
    private final AtomicLong recallCount = new AtomicLong();
    private final AtomicLong evictionDecisionCount = new AtomicLong();
    private final AtomicLong evictionDecisionLoggedCount = new AtomicLong();
    private final AtomicLong namespaceIsolationViolations = new AtomicLong();
    private final AtomicLong checkpointRecoverySuccessCount = new AtomicLong();
    private final AtomicLong checkpointRecoveryFailureCount = new AtomicLong();
    private final AtomicLong persistenceSuccessCount = new AtomicLong();
    private final AtomicLong persistenceFailureCount = new AtomicLong();
    private final AtomicLong storeLatencyNanosMax = new AtomicLong();
    private final AtomicLong recallLatencyNanosMax = new AtomicLong();
    private final RollingPercentile storeLatencyPercentiles = new RollingPercentile(LATENCY_SAMPLE_WINDOW);
    private final RollingPercentile recallLatencyPercentiles = new RollingPercentile(LATENCY_SAMPLE_WINDOW);
    private final AtomicLong tieredColdOnlySelections = new AtomicLong();
    private final AtomicLong tieredHotOnlySelections = new AtomicLong();
    private final AtomicLong tieredExpandedSelections = new AtomicLong();
    private final AtomicLong admissionPlanningGateWaitCount = new AtomicLong();
    private final AtomicLong admissionPlanningGateWaitNanosTotal = new AtomicLong();
    private final AtomicLong admissionPlanningGateWaitNanosMax = new AtomicLong();
    private final AtomicLong admissionLockWaitNanosTotal = new AtomicLong();
    private final AtomicLong admissionLockWaitNanosMax = new AtomicLong();
    private final AtomicLong admissionLockHoldNanosTotal = new AtomicLong();
    private final AtomicLong admissionLockHoldNanosMax = new AtomicLong();
    private final AtomicLong admissionPlanningNanosTotal = new AtomicLong();
    private final AtomicLong admissionPlanningNanosMax = new AtomicLong();
    private final AtomicLong admissionDetailedSnapshotLockHoldNanosTotal = new AtomicLong();
    private final AtomicLong admissionDetailedSnapshotLockHoldNanosMax = new AtomicLong();
    private final AtomicLong admissionDetailedSnapshotFreezeNanosTotal = new AtomicLong();
    private final AtomicLong admissionDetailedSnapshotFreezeNanosMax = new AtomicLong();
    private final AtomicLong admissionCommitLockHoldNanosTotal = new AtomicLong();
    private final AtomicLong admissionCommitLockHoldNanosMax = new AtomicLong();
    private final AtomicLong admissionRequestCount = new AtomicLong();
    private final AtomicLong admissionDirectAttemptCount = new AtomicLong();
    private final AtomicLong admissionDirectCommitCount = new AtomicLong();
    private final AtomicLong admissionDirectEscalationCount = new AtomicLong();
    private final AtomicLong admissionDirectRejectionCount = new AtomicLong();
    private final AtomicLong admissionOptimisticAttemptCount = new AtomicLong();
    private final AtomicLong admissionOptimisticCommitCount = new AtomicLong();
    private final AtomicLong admissionLockAcquisitionCount = new AtomicLong();
    private final AtomicLong admissionPlanningCount = new AtomicLong();
    private final AtomicLong admissionDetailedSnapshotCount = new AtomicLong();
    private final AtomicLong admissionDetailedSnapshotFreezeCount = new AtomicLong();
    private final AtomicLong admissionCommitLockCount = new AtomicLong();
    private final AtomicLong admissionOptimisticConflictCount = new AtomicLong();
    private final AtomicLong admissionFallbackCount = new AtomicLong();
    private final AtomicReference<Double> regretRate = new AtomicReference<>(0.0);
    private final AtomicReference<Double> shadowLift = new AtomicReference<>(0.0);
    private final AtomicReference<Double> baselineLift = new AtomicReference<>(0.0);
    private final AtomicReference<Double> baselineLiftSustainedRatio = new AtomicReference<>(0.0);

    public MemorySloTracker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void bind() {
        Gauge.builder("vortex.hmc.slo.eviction.log.coverage", this, tracker -> tracker.evictionLogCoverage())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.namespace.isolation.violations", namespaceIsolationViolations, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.recovery.success.rate", this, tracker -> tracker.recoverySuccessRate())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.checkpoint.recovery.success.rate", this, tracker -> tracker.checkpointRecoverySuccessRate())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.persistence.success.rate", this, tracker -> tracker.persistenceSuccessRate())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.durability.success.rate", this, tracker -> tracker.durabilitySuccessRate())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.store.latency.max.ms", storeLatencyNanosMax, nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.store.latency.p95.ms", this, tracker -> tracker.storeLatencyPercentiles.percentileMillis(0.95))
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.store.latency.p99.ms", this, tracker -> tracker.storeLatencyPercentiles.percentileMillis(0.99))
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.recall.latency.max.ms", recallLatencyNanosMax, nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.recall.latency.p95.ms", this, tracker -> tracker.recallLatencyPercentiles.percentileMillis(0.95))
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.recall.latency.p99.ms", this, tracker -> tracker.recallLatencyPercentiles.percentileMillis(0.99))
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.tier.cold.only.count", tieredColdOnlySelections, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.tier.hot.only.count", tieredHotOnlySelections, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.tier.expansion.count", tieredExpandedSelections, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.planning.gate.wait.count",
                        admissionPlanningGateWaitCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.planning.gate.wait.total.ms",
                        admissionPlanningGateWaitNanosTotal,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.planning.gate.wait.max.ms",
                        admissionPlanningGateWaitNanosMax,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.lock.wait.total.ms", admissionLockWaitNanosTotal,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.lock.wait.max.ms", admissionLockWaitNanosMax,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.lock.hold.total.ms", admissionLockHoldNanosTotal,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.lock.hold.max.ms", admissionLockHoldNanosMax,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.planning.total.ms", admissionPlanningNanosTotal,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.planning.max.ms", admissionPlanningNanosMax,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.snapshot.detailed.lock.hold.total.ms",
                        admissionDetailedSnapshotLockHoldNanosTotal,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.snapshot.detailed.lock.hold.max.ms",
                        admissionDetailedSnapshotLockHoldNanosMax,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.snapshot.detailed.freeze.total.ms",
                        admissionDetailedSnapshotFreezeNanosTotal,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.snapshot.detailed.freeze.max.ms",
                        admissionDetailedSnapshotFreezeNanosMax,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.commit.lock.hold.total.ms",
                        admissionCommitLockHoldNanosTotal,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.commit.lock.hold.max.ms",
                        admissionCommitLockHoldNanosMax,
                        nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.request.count", admissionRequestCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.direct.attempt.count",
                        admissionDirectAttemptCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.direct.commit.count",
                        admissionDirectCommitCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.direct.escalation.count",
                        admissionDirectEscalationCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.direct.rejection.count",
                        admissionDirectRejectionCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.optimistic.attempt.count",
                        admissionOptimisticAttemptCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.optimistic.commit.count",
                        admissionOptimisticCommitCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.lock.acquisition.count",
                        admissionLockAcquisitionCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.planning.count", admissionPlanningCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.optimistic.conflict.count",
                        admissionOptimisticConflictCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.admission.fallback.count", admissionFallbackCount, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.eviction.regret.rate", regretRate, AtomicReference::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.shadow.relative.lift", shadowLift, AtomicReference::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.baseline.relative.lift", baselineLift, AtomicReference::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.baseline.sustained.ratio", baselineLiftSustainedRatio, AtomicReference::get)
                .register(meterRegistry);
    }

    public void recordStoreLatency(long nanos) {
        storeCount.incrementAndGet();
        storeLatencyNanosMax.accumulateAndGet(nanos, Math::max);
        storeLatencyPercentiles.record(nanos);
    }

    public void recordRecallLatency(long nanos) {
        recallCount.incrementAndGet();
        recallLatencyNanosMax.accumulateAndGet(nanos, Math::max);
        recallLatencyPercentiles.record(nanos);
    }

    public void recordEvictionDecisionLogged() {
        evictionDecisionLoggedCount.incrementAndGet();
    }

    public void recordEvictionDecisionAttempt() {
        evictionDecisionCount.incrementAndGet();
    }

    public void recordNamespaceIsolationViolation() {
        namespaceIsolationViolations.incrementAndGet();
    }

    public void recordCheckpointRecoveryResult(boolean success) {
        if (success) {
            checkpointRecoverySuccessCount.incrementAndGet();
        } else {
            checkpointRecoveryFailureCount.incrementAndGet();
        }
    }

    public void recordPersistenceResult(boolean success) {
        if (success) {
            persistenceSuccessCount.incrementAndGet();
        } else {
            persistenceFailureCount.incrementAndGet();
        }
    }

    public void recordRegretRate(double currentRegretRate) {
        regretRate.set(currentRegretRate);
    }

    public void recordLearningLift(double currentShadowLift, double currentBaselineLift) {
        recordLearningLift(currentShadowLift, currentBaselineLift, 0.0);
    }

    public void recordLearningLift(
            double currentShadowLift,
            double currentBaselineLift,
            double currentBaselineLiftSustainedRatio) {
        shadowLift.set(currentShadowLift);
        baselineLift.set(currentBaselineLift);
        baselineLiftSustainedRatio.set(currentBaselineLiftSustainedRatio);
    }

    public void recordTieredSelection(boolean coldOnly, boolean hotOnly, boolean expanded) {
        if (coldOnly) {
            tieredColdOnlySelections.incrementAndGet();
        }
        if (hotOnly) {
            tieredHotOnlySelections.incrementAndGet();
        }
        if (expanded) {
            tieredExpandedSelections.incrementAndGet();
        }
    }

    public void recordAdmissionLockWait(long nanos) {
        admissionLockAcquisitionCount.incrementAndGet();
        recordDuration(nanos, admissionLockWaitNanosTotal, admissionLockWaitNanosMax);
    }

    public void recordAdmissionPlanningGateWait(long nanos) {
        admissionPlanningGateWaitCount.incrementAndGet();
        recordDuration(
                nanos,
                admissionPlanningGateWaitNanosTotal,
                admissionPlanningGateWaitNanosMax);
    }

    public void recordAdmissionLockHold(long nanos) {
        recordDuration(nanos, admissionLockHoldNanosTotal, admissionLockHoldNanosMax);
    }

    public void recordAdmissionPlanning(long nanos) {
        admissionPlanningCount.incrementAndGet();
        recordDuration(nanos, admissionPlanningNanosTotal, admissionPlanningNanosMax);
    }

    public void recordAdmissionDetailedSnapshotLockHold(long nanos) {
        admissionDetailedSnapshotCount.incrementAndGet();
        recordDuration(
                nanos,
                admissionDetailedSnapshotLockHoldNanosTotal,
                admissionDetailedSnapshotLockHoldNanosMax);
    }

    public void recordAdmissionDetailedSnapshotFreeze(long nanos) {
        admissionDetailedSnapshotFreezeCount.incrementAndGet();
        recordDuration(
                nanos,
                admissionDetailedSnapshotFreezeNanosTotal,
                admissionDetailedSnapshotFreezeNanosMax);
    }

    public void recordAdmissionCommitLockHold(long nanos) {
        admissionCommitLockCount.incrementAndGet();
        recordDuration(
                nanos,
                admissionCommitLockHoldNanosTotal,
                admissionCommitLockHoldNanosMax);
    }

    public void recordAdmissionRequest() {
        admissionRequestCount.incrementAndGet();
    }

    public void recordAdmissionDirectAttempt() {
        admissionDirectAttemptCount.incrementAndGet();
    }

    public void recordAdmissionDirectCommit() {
        admissionDirectCommitCount.incrementAndGet();
    }

    public void recordAdmissionDirectEscalation() {
        admissionDirectEscalationCount.incrementAndGet();
    }

    public void recordAdmissionDirectRejection() {
        admissionDirectRejectionCount.incrementAndGet();
    }

    public void recordAdmissionOptimisticAttempt() {
        admissionOptimisticAttemptCount.incrementAndGet();
    }

    public void recordAdmissionOptimisticCommit() {
        admissionOptimisticCommitCount.incrementAndGet();
    }

    public void recordAdmissionOptimisticConflict() {
        admissionOptimisticConflictCount.incrementAndGet();
    }

    public void recordAdmissionFallback() {
        admissionFallbackCount.incrementAndGet();
    }

    public SloSnapshot snapshot() {
        return new SloSnapshot(
                storeCount.get(),
                recallCount.get(),
                evictionLogCoverage(),
                regretRate.get(),
                shadowLift.get(),
                baselineLift.get(),
                baselineLiftSustainedRatio.get(),
                namespaceIsolationViolations.get(),
                recoverySuccessRate(),
                checkpointRecoverySuccessRate(),
                persistenceSuccessRate(),
                durabilitySuccessRate(),
                storeLatencyNanosMax.get() / 1_000_000.0,
                storeLatencyPercentiles.percentileMillis(0.95),
                storeLatencyPercentiles.percentileMillis(0.99),
                recallLatencyNanosMax.get() / 1_000_000.0,
                recallLatencyPercentiles.percentileMillis(0.95),
                recallLatencyPercentiles.percentileMillis(0.99),
                tieredColdOnlySelections.get(),
                tieredHotOnlySelections.get(),
                tieredExpandedSelections.get(),
                admissionMetricsSnapshot().toSloSnapshot()
        );
    }

    public AdmissionMetricsSnapshot admissionMetricsSnapshot() {
        return new AdmissionMetricsSnapshot(
                admissionRequestCount.get(),
                admissionDirectAttemptCount.get(),
                admissionDirectCommitCount.get(),
                admissionDirectEscalationCount.get(),
                admissionDirectRejectionCount.get(),
                admissionOptimisticAttemptCount.get(),
                admissionOptimisticCommitCount.get(),
                admissionOptimisticConflictCount.get(),
                admissionFallbackCount.get(),
                admissionPlanningGateWaitCount.get(),
                admissionPlanningGateWaitNanosTotal.get(),
                admissionPlanningGateWaitNanosMax.get(),
                admissionLockAcquisitionCount.get(),
                admissionLockWaitNanosTotal.get(),
                admissionLockWaitNanosMax.get(),
                admissionLockHoldNanosTotal.get(),
                admissionLockHoldNanosMax.get(),
                admissionPlanningCount.get(),
                admissionPlanningNanosTotal.get(),
                admissionPlanningNanosMax.get(),
                admissionDetailedSnapshotCount.get(),
                admissionDetailedSnapshotLockHoldNanosTotal.get(),
                admissionDetailedSnapshotLockHoldNanosMax.get(),
                admissionDetailedSnapshotFreezeCount.get(),
                admissionDetailedSnapshotFreezeNanosTotal.get(),
                admissionDetailedSnapshotFreezeNanosMax.get(),
                admissionCommitLockCount.get(),
                admissionCommitLockHoldNanosTotal.get(),
                admissionCommitLockHoldNanosMax.get());
    }

    private double evictionLogCoverage() {
        long decisions = evictionDecisionCount.get();
        return decisions == 0 ? 1.0 : evictionDecisionLoggedCount.get() / (double) decisions;
    }

    private double recoverySuccessRate() {
        return checkpointRecoverySuccessRate();
    }

    private double checkpointRecoverySuccessRate() {
        long total = checkpointRecoverySuccessCount.get() + checkpointRecoveryFailureCount.get();
        return total == 0 ? 1.0 : checkpointRecoverySuccessCount.get() / (double) total;
    }

    private double persistenceSuccessRate() {
        long total = persistenceSuccessCount.get() + persistenceFailureCount.get();
        return total == 0 ? 1.0 : persistenceSuccessCount.get() / (double) total;
    }

    private double durabilitySuccessRate() {
        long success = checkpointRecoverySuccessCount.get() + persistenceSuccessCount.get();
        long total = success + checkpointRecoveryFailureCount.get() + persistenceFailureCount.get();
        return total == 0 ? 1.0 : success / (double) total;
    }

    private void recordDuration(long nanos, AtomicLong total, AtomicLong max) {
        long nonNegativeNanos = Math.max(0L, nanos);
        total.addAndGet(nonNegativeNanos);
        max.accumulateAndGet(nonNegativeNanos, Math::max);
    }

    public record SloSnapshot(
            long storeCount,
            long recallCount,
            double evictionLogCoverage,
            double regretRate,
            double shadowRelativeLift,
            double baselineRelativeLift,
            double baselineLiftSustainedRatio,
            long namespaceIsolationViolations,
            double recoverySuccessRate,
            double checkpointRecoverySuccessRate,
            double persistenceSuccessRate,
            double durabilitySuccessRate,
            double storeLatencyMaxMs,
            double storeLatencyP95Ms,
            double storeLatencyP99Ms,
            double recallLatencyMaxMs,
            double recallLatencyP95Ms,
            double recallLatencyP99Ms,
            long tieredColdOnlySelections,
            long tieredHotOnlySelections,
            long tieredExpandedSelections,
            AdmissionSloSnapshot admission) {

        public SloSnapshot(
                long storeCount,
                long recallCount,
                double evictionLogCoverage,
                double regretRate,
                double shadowRelativeLift,
                double baselineRelativeLift,
                double baselineLiftSustainedRatio,
                long namespaceIsolationViolations,
                double recoverySuccessRate,
                double checkpointRecoverySuccessRate,
                double persistenceSuccessRate,
                double durabilitySuccessRate,
                double storeLatencyMaxMs,
                double storeLatencyP95Ms,
                double storeLatencyP99Ms,
                double recallLatencyMaxMs,
                double recallLatencyP95Ms,
                double recallLatencyP99Ms,
                long tieredColdOnlySelections,
                long tieredHotOnlySelections,
                long tieredExpandedSelections) {
            this(
                    storeCount,
                    recallCount,
                    evictionLogCoverage,
                    regretRate,
                    shadowRelativeLift,
                    baselineRelativeLift,
                    baselineLiftSustainedRatio,
                    namespaceIsolationViolations,
                    recoverySuccessRate,
                    checkpointRecoverySuccessRate,
                    persistenceSuccessRate,
                    durabilitySuccessRate,
                    storeLatencyMaxMs,
                    storeLatencyP95Ms,
                    storeLatencyP99Ms,
                    recallLatencyMaxMs,
                    recallLatencyP95Ms,
                    recallLatencyP99Ms,
                    tieredColdOnlySelections,
                    tieredHotOnlySelections,
                    tieredExpandedSelections,
                    AdmissionSloSnapshot.empty());
        }
    }

    public record AdmissionMetricsSnapshot(
            long requestCount,
            long directAttemptCount,
            long directCommitCount,
            long directEscalationCount,
            long directRejectionCount,
            long optimisticAttemptCount,
            long optimisticCommitCount,
            long optimisticConflictCount,
            long fallbackCount,
            long planningGateWaitCount,
            long planningGateWaitNanosTotal,
            long planningGateWaitNanosMax,
            long lockAcquisitionCount,
            long lockWaitNanosTotal,
            long lockWaitNanosMax,
            long lockHoldNanosTotal,
            long lockHoldNanosMax,
            long planningCount,
            long planningNanosTotal,
            long planningNanosMax,
            long detailedSnapshotCount,
            long detailedSnapshotLockHoldNanosTotal,
            long detailedSnapshotLockHoldNanosMax,
            long detailedSnapshotFreezeCount,
            long detailedSnapshotFreezeNanosTotal,
            long detailedSnapshotFreezeNanosMax,
            long commitLockCount,
            long commitLockHoldNanosTotal,
            long commitLockHoldNanosMax) {

        private AdmissionSloSnapshot toSloSnapshot() {
            return new AdmissionSloSnapshot(
                    requestCount,
                    directAttemptCount,
                    directCommitCount,
                    directEscalationCount,
                    directRejectionCount,
                    optimisticAttemptCount,
                    optimisticCommitCount,
                    optimisticConflictCount,
                    fallbackCount,
                    planningGateWaitCount,
                    nanosToMillis(planningGateWaitNanosTotal),
                    averageMillis(planningGateWaitNanosTotal, planningGateWaitCount),
                    nanosToMillis(planningGateWaitNanosMax),
                    lockAcquisitionCount,
                    nanosToMillis(lockWaitNanosTotal),
                    averageMillis(lockWaitNanosTotal, lockAcquisitionCount),
                    nanosToMillis(lockWaitNanosMax),
                    nanosToMillis(lockHoldNanosTotal),
                    averageMillis(lockHoldNanosTotal, lockAcquisitionCount),
                    nanosToMillis(lockHoldNanosMax),
                    planningCount,
                    nanosToMillis(planningNanosTotal),
                    averageMillis(planningNanosTotal, planningCount),
                    nanosToMillis(planningNanosMax),
                    ratio(optimisticConflictCount, optimisticAttemptCount),
                    ratio(fallbackCount, requestCount));
        }
    }

    public record AdmissionSloSnapshot(
            long requestCount,
            long directAttemptCount,
            long directCommitCount,
            long directEscalationCount,
            long directRejectionCount,
            long optimisticAttemptCount,
            long optimisticCommitCount,
            long optimisticConflictCount,
            long fallbackCount,
            long planningGateWaitCount,
            double planningGateWaitTotalMs,
            double planningGateWaitAverageMs,
            double planningGateWaitMaxMs,
            long lockAcquisitionCount,
            double lockWaitTotalMs,
            double lockWaitAverageMs,
            double lockWaitMaxMs,
            double lockHoldTotalMs,
            double lockHoldAverageMs,
            double lockHoldMaxMs,
            long planningCount,
            double planningTotalMs,
            double planningAverageMs,
            double planningMaxMs,
            double optimisticConflictRate,
            double fallbackRate) {

        private static AdmissionSloSnapshot empty() {
            return new AdmissionMetricsSnapshot(
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
                    .toSloSnapshot();
        }
    }

    private static double nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000.0;
    }

    private static double averageMillis(long nanos, long count) {
        return count == 0L ? 0.0 : nanosToMillis(nanos) / count;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0 : numerator / (double) denominator;
    }

    private static final class RollingPercentile {
        private final int capacity;
        private final long[] ringBuffer;
        private final List<Long> sortedSamples;
        private int nextWriteIndex;
        private int size;

        private RollingPercentile(int capacity) {
            this.capacity = capacity;
            this.ringBuffer = new long[capacity];
            this.sortedSamples = new ArrayList<>(capacity);
        }

        synchronized void record(long nanos) {
            if (size == capacity) {
                long evicted = ringBuffer[nextWriteIndex];
                removeSorted(evicted);
            } else {
                size++;
            }
            ringBuffer[nextWriteIndex] = nanos;
            insertSorted(nanos);
            nextWriteIndex = (nextWriteIndex + 1) % capacity;
        }

        synchronized double percentileMillis(double percentile) {
            if (sortedSamples.isEmpty()) {
                return 0.0;
            }
            int index = (int) Math.ceil(percentile * sortedSamples.size()) - 1;
            index = Math.max(0, Math.min(sortedSamples.size() - 1, index));
            return sortedSamples.get(index) / 1_000_000.0;
        }

        private void insertSorted(long nanos) {
            int insertionPoint = upperBound(nanos);
            sortedSamples.add(insertionPoint, nanos);
        }

        private void removeSorted(long nanos) {
            int index = lowerBound(nanos);
            if (index < sortedSamples.size() && sortedSamples.get(index) == nanos) {
                sortedSamples.remove(index);
            }
        }

        private int lowerBound(long nanos) {
            int low = 0;
            int high = sortedSamples.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (sortedSamples.get(mid) < nanos) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        private int upperBound(long nanos) {
            int low = 0;
            int high = sortedSamples.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (sortedSamples.get(mid) <= nanos) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }
    }
}
