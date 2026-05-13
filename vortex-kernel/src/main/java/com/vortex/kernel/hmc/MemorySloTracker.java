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
    private final AtomicLong recoverySuccessCount = new AtomicLong();
    private final AtomicLong recoveryFailureCount = new AtomicLong();
    private final AtomicLong storeLatencyNanosMax = new AtomicLong();
    private final AtomicLong recallLatencyNanosMax = new AtomicLong();
    private final RollingPercentile storeLatencyPercentiles = new RollingPercentile(LATENCY_SAMPLE_WINDOW);
    private final RollingPercentile recallLatencyPercentiles = new RollingPercentile(LATENCY_SAMPLE_WINDOW);
    private final AtomicLong tieredColdOnlySelections = new AtomicLong();
    private final AtomicLong tieredHotOnlySelections = new AtomicLong();
    private final AtomicLong tieredExpandedSelections = new AtomicLong();
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
                storeLatencyNanosMax.get() / 1_000_000.0,
                storeLatencyPercentiles.percentileMillis(0.95),
                storeLatencyPercentiles.percentileMillis(0.99),
                recallLatencyNanosMax.get() / 1_000_000.0,
                recallLatencyPercentiles.percentileMillis(0.95),
                recallLatencyPercentiles.percentileMillis(0.99),
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
            double baselineLiftSustainedRatio,
            long namespaceIsolationViolations,
            double recoverySuccessRate,
            double storeLatencyMaxMs,
            double storeLatencyP95Ms,
            double storeLatencyP99Ms,
            double recallLatencyMaxMs,
            double recallLatencyP95Ms,
            double recallLatencyP99Ms,
            long tieredColdOnlySelections,
            long tieredHotOnlySelections,
            long tieredExpandedSelections) {
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
