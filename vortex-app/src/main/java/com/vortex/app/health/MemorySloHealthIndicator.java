package com.vortex.app.health;

import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.hmc.MemorySloTracker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class MemorySloHealthIndicator implements HealthIndicator {

    private final Supplier<MemorySloTracker.SloSnapshot> snapshotSupplier;
    private final double minEvictionLogCoverage;
    private final double maxEvictionRegretRate;
    private final double maxNamespaceIsolationViolations;
    private final double minRecoverySuccessRate;
    private final double maxStoreLatencyP99Ms;
    private final double maxRecallLatencyP99Ms;
    private final double minShadowRelativeLift;
    private final double minBaselineRelativeLift;
    private final double minBaselineSustainedRatio;

    public MemorySloHealthIndicator(
            HierarchicalMemoryController hmc,
            @Value("${vortex.kernel.slo.min-eviction-log-coverage:1.0}") double minEvictionLogCoverage,
            @Value("${vortex.kernel.slo.max-eviction-regret-rate:0.05}") double maxEvictionRegretRate,
            @Value("${vortex.kernel.slo.max-namespace-isolation-violations:0}") double maxNamespaceIsolationViolations,
            @Value("${vortex.kernel.slo.min-recovery-success-rate:1.0}") double minRecoverySuccessRate,
            @Value("${vortex.kernel.slo.max-store-latency-p99-ms:10.0}") double maxStoreLatencyP99Ms,
            @Value("${vortex.kernel.slo.max-recall-latency-p99-ms:10.0}") double maxRecallLatencyP99Ms,
            @Value("${vortex.kernel.slo.min-shadow-relative-lift:-1.0}") double minShadowRelativeLift,
            @Value("${vortex.kernel.slo.min-baseline-relative-lift:0.20}") double minBaselineRelativeLift,
            @Value("${vortex.kernel.slo.min-baseline-sustained-ratio:0.90}") double minBaselineSustainedRatio) {
        this(
                hmc::sloSnapshot,
                minEvictionLogCoverage,
                maxEvictionRegretRate,
                maxNamespaceIsolationViolations,
                minRecoverySuccessRate,
                maxStoreLatencyP99Ms,
                maxRecallLatencyP99Ms,
                minShadowRelativeLift,
                minBaselineRelativeLift,
                minBaselineSustainedRatio);
    }

    MemorySloHealthIndicator(
            Supplier<MemorySloTracker.SloSnapshot> snapshotSupplier,
            @Value("${vortex.kernel.slo.min-eviction-log-coverage:1.0}") double minEvictionLogCoverage,
            @Value("${vortex.kernel.slo.max-eviction-regret-rate:0.05}") double maxEvictionRegretRate,
            @Value("${vortex.kernel.slo.max-namespace-isolation-violations:0}") double maxNamespaceIsolationViolations,
            @Value("${vortex.kernel.slo.min-recovery-success-rate:1.0}") double minRecoverySuccessRate,
            @Value("${vortex.kernel.slo.max-store-latency-p99-ms:10.0}") double maxStoreLatencyP99Ms,
            @Value("${vortex.kernel.slo.max-recall-latency-p99-ms:10.0}") double maxRecallLatencyP99Ms,
            @Value("${vortex.kernel.slo.min-shadow-relative-lift:-1.0}") double minShadowRelativeLift,
            @Value("${vortex.kernel.slo.min-baseline-relative-lift:0.20}") double minBaselineRelativeLift,
            @Value("${vortex.kernel.slo.min-baseline-sustained-ratio:0.90}") double minBaselineSustainedRatio) {
        this.snapshotSupplier = snapshotSupplier;
        this.minEvictionLogCoverage = minEvictionLogCoverage;
        this.maxEvictionRegretRate = maxEvictionRegretRate;
        this.maxNamespaceIsolationViolations = maxNamespaceIsolationViolations;
        this.minRecoverySuccessRate = minRecoverySuccessRate;
        this.maxStoreLatencyP99Ms = maxStoreLatencyP99Ms;
        this.maxRecallLatencyP99Ms = maxRecallLatencyP99Ms;
        this.minShadowRelativeLift = minShadowRelativeLift;
        this.minBaselineRelativeLift = minBaselineRelativeLift;
        this.minBaselineSustainedRatio = minBaselineSustainedRatio;
    }

    @Override
    public Health health() {
        MemorySloTracker.SloSnapshot snapshot = snapshotSupplier.get();
        boolean healthy = snapshot.evictionLogCoverage() >= minEvictionLogCoverage
                && snapshot.regretRate() <= maxEvictionRegretRate
                && snapshot.namespaceIsolationViolations() <= maxNamespaceIsolationViolations
                && snapshot.recoverySuccessRate() >= minRecoverySuccessRate
                && snapshot.storeLatencyP99Ms() <= maxStoreLatencyP99Ms
                && snapshot.recallLatencyP99Ms() <= maxRecallLatencyP99Ms
                && snapshot.shadowRelativeLift() >= minShadowRelativeLift
                && snapshot.baselineRelativeLift() >= minBaselineRelativeLift
                && snapshot.baselineLiftSustainedRatio() >= minBaselineSustainedRatio;

        Health.Builder builder = healthy ? Health.up() : Health.down();
        return builder
                .withDetail("evictionLogCoverage", snapshot.evictionLogCoverage())
                .withDetail("regretRate", snapshot.regretRate())
                .withDetail("namespaceIsolationViolations", snapshot.namespaceIsolationViolations())
                .withDetail("recoverySuccessRate", snapshot.recoverySuccessRate())
                .withDetail("storeLatencyP99Ms", snapshot.storeLatencyP99Ms())
                .withDetail("recallLatencyP99Ms", snapshot.recallLatencyP99Ms())
                .withDetail("shadowRelativeLift", snapshot.shadowRelativeLift())
                .withDetail("baselineRelativeLift", snapshot.baselineRelativeLift())
                .withDetail("baselineLiftSustainedRatio", snapshot.baselineLiftSustainedRatio())
                .build();
    }
}
