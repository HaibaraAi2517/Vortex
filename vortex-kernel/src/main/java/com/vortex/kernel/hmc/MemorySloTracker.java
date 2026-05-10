package com.vortex.kernel.hmc;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MemorySloTracker {

    private final MeterRegistry meterRegistry;
    private final AtomicLong storeCount = new AtomicLong();
    private final AtomicLong recallCount = new AtomicLong();
    private final AtomicLong evictionDecisionCount = new AtomicLong();
    private final AtomicLong evictionDecisionLoggedCount = new AtomicLong();
    private final AtomicLong namespaceIsolationViolations = new AtomicLong();
    private final AtomicLong recoverySuccessCount = new AtomicLong();
    private final AtomicLong recoveryFailureCount = new AtomicLong();
    private final AtomicLong storeLatencyNanosMax = new AtomicLong();
    private final AtomicLong recallLatencyNanosMax = new AtomicLong();
    private final AtomicLong tieredColdOnlySelections = new AtomicLong();
    private final AtomicLong tieredHotOnlySelections = new AtomicLong();
    private final AtomicLong tieredExpandedSelections = new AtomicLong();
    private final AtomicReference<Double> regretRate = new AtomicReference<>(0.0);
    private final AtomicReference<Double> shadowLift = new AtomicReference<>(0.0);
    private final AtomicReference<Double> baselineLift = new AtomicReference<>(0.0);

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
        Gauge.builder("vortex.hmc.slo.store.latency.max.ms", storeLatencyNanosMax, nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.recall.latency.max.ms", recallLatencyNanosMax, nanos -> nanos.get() / 1_000_000.0)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.tier.cold.only.count", tieredColdOnlySelections, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.tier.hot.only.count", tieredHotOnlySelections, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.tier.expansion.count", tieredExpandedSelections, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.eviction.regret.rate", regretRate, AtomicReference::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.shadow.relative.lift", shadowLift, AtomicReference::get)
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.slo.baseline.relative.lift", baselineLift, AtomicReference::get)
                .register(meterRegistry);
    }

    public void recordStoreLatency(long nanos) {
        storeCount.incrementAndGet();
        storeLatencyNanosMax.accumulateAndGet(nanos, Math::max);
    }

    public void recordRecallLatency(long nanos) {
        recallCount.incrementAndGet();
        recallLatencyNanosMax.accumulateAndGet(nanos, Math::max);
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

    public void recordRecoveryResult(boolean success) {
        if (success) {
            recoverySuccessCount.incrementAndGet();
        } else {
            recoveryFailureCount.incrementAndGet();
        }
    }

    public void recordRegretRate(double currentRegretRate) {
        regretRate.set(currentRegretRate);
    }

    public void recordLearningLift(double currentShadowLift, double currentBaselineLift) {
        shadowLift.set(currentShadowLift);
        baselineLift.set(currentBaselineLift);
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

    public SloSnapshot snapshot() {
        return new SloSnapshot(
                storeCount.get(),
                recallCount.get(),
                evictionLogCoverage(),
                regretRate.get(),
                shadowLift.get(),
                baselineLift.get(),
                namespaceIsolationViolations.get(),
                recoverySuccessRate(),
                storeLatencyNanosMax.get() / 1_000_000.0,
                recallLatencyNanosMax.get() / 1_000_000.0,
                tieredColdOnlySelections.get(),
                tieredHotOnlySelections.get(),
                tieredExpandedSelections.get()
        );
    }

    private double evictionLogCoverage() {
        long decisions = evictionDecisionCount.get();
        return decisions == 0 ? 1.0 : evictionDecisionLoggedCount.get() / (double) decisions;
    }

    private double recoverySuccessRate() {
        long total = recoverySuccessCount.get() + recoveryFailureCount.get();
        return total == 0 ? 1.0 : recoverySuccessCount.get() / (double) total;
    }

    public record SloSnapshot(
            long storeCount,
            long recallCount,
            double evictionLogCoverage,
            double regretRate,
            double shadowRelativeLift,
            double baselineRelativeLift,
            long namespaceIsolationViolations,
            double recoverySuccessRate,
            double storeLatencyMaxMs,
            double recallLatencyMaxMs,
            long tieredColdOnlySelections,
            long tieredHotOnlySelections,
            long tieredExpandedSelections) {
    }
}
