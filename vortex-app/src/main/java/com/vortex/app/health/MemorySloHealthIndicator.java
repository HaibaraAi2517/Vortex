package com.vortex.app.health;

import com.vortex.common.health.MemoryDiagnosticSignal;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.hmc.MemoryDiagnosticsCollector;
import com.vortex.kernel.hmc.MemorySloTracker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

@Component
public class MemorySloHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED");

    private final Supplier<MemorySloTracker.SloSnapshot> snapshotSupplier;
    private final Supplier<MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot> diagnosticsSupplier;
    private final MemoryHealthStateLogger stateLogger;
    private final double minEvictionLogCoverage;
    private final double maxEvictionRegretRate;
    private final double maxNamespaceIsolationViolations;
    private final double minCheckpointRecoverySuccessRate;
    private final double minPersistenceSuccessRate;
    private final double maxStoreLatencyP99Ms;
    private final double maxRecallLatencyP99Ms;
    private final double minShadowRelativeLift;
    private final double minBaselineRelativeLift;
    private final double minBaselineSustainedRatio;
    private final long minLearningSamplesForHealth;

    @Autowired
    public MemorySloHealthIndicator(
            HierarchicalMemoryController hmc,
            MemoryHealthStateLogger stateLogger,
            @Value("${vortex.kernel.slo.min-eviction-log-coverage:1.0}") double minEvictionLogCoverage,
            @Value("${vortex.kernel.slo.max-eviction-regret-rate:0.05}") double maxEvictionRegretRate,
            @Value("${vortex.kernel.slo.max-namespace-isolation-violations:0}") double maxNamespaceIsolationViolations,
            @Value("${vortex.kernel.slo.min-checkpoint-recovery-success-rate:${vortex.kernel.slo.min-recovery-success-rate:1.0}}") double minCheckpointRecoverySuccessRate,
            @Value("${vortex.kernel.slo.min-persistence-success-rate:1.0}") double minPersistenceSuccessRate,
            @Value("${vortex.kernel.slo.max-store-latency-p99-ms:10.0}") double maxStoreLatencyP99Ms,
            @Value("${vortex.kernel.slo.max-recall-latency-p99-ms:10.0}") double maxRecallLatencyP99Ms,
            @Value("${vortex.kernel.slo.min-shadow-relative-lift:-1.0}") double minShadowRelativeLift,
            @Value("${vortex.kernel.slo.min-baseline-relative-lift:0.20}") double minBaselineRelativeLift,
            @Value("${vortex.kernel.slo.min-baseline-sustained-ratio:0.90}") double minBaselineSustainedRatio,
            @Value("${vortex.kernel.slo.min-learning-samples-for-health:1}") long minLearningSamplesForHealth) {
        this(
                hmc::sloSnapshot,
                hmc::diagnosticsSnapshot,
                stateLogger,
                minEvictionLogCoverage,
                maxEvictionRegretRate,
                maxNamespaceIsolationViolations,
                minCheckpointRecoverySuccessRate,
                minPersistenceSuccessRate,
                maxStoreLatencyP99Ms,
                maxRecallLatencyP99Ms,
                minShadowRelativeLift,
                minBaselineRelativeLift,
                minBaselineSustainedRatio,
                minLearningSamplesForHealth);
    }

    MemorySloHealthIndicator(
            Supplier<MemorySloTracker.SloSnapshot> snapshotSupplier,
            Supplier<MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot> diagnosticsSupplier,
            MemoryHealthStateLogger stateLogger,
            double minEvictionLogCoverage,
            double maxEvictionRegretRate,
            double maxNamespaceIsolationViolations,
            double minCheckpointRecoverySuccessRate,
            double minPersistenceSuccessRate,
            double maxStoreLatencyP99Ms,
            double maxRecallLatencyP99Ms,
            double minShadowRelativeLift,
            double minBaselineRelativeLift,
            double minBaselineSustainedRatio,
            long minLearningSamplesForHealth) {
        this.snapshotSupplier = snapshotSupplier;
        this.diagnosticsSupplier = diagnosticsSupplier;
        this.stateLogger = stateLogger;
        this.minEvictionLogCoverage = minEvictionLogCoverage;
        this.maxEvictionRegretRate = maxEvictionRegretRate;
        this.maxNamespaceIsolationViolations = maxNamespaceIsolationViolations;
        this.minCheckpointRecoverySuccessRate = minCheckpointRecoverySuccessRate;
        this.minPersistenceSuccessRate = minPersistenceSuccessRate;
        this.maxStoreLatencyP99Ms = maxStoreLatencyP99Ms;
        this.maxRecallLatencyP99Ms = maxRecallLatencyP99Ms;
        this.minShadowRelativeLift = minShadowRelativeLift;
        this.minBaselineRelativeLift = minBaselineRelativeLift;
        this.minBaselineSustainedRatio = minBaselineSustainedRatio;
        this.minLearningSamplesForHealth = minLearningSamplesForHealth;
    }

    @Override
    public Health health() {
        MemorySloTracker.SloSnapshot snapshot = snapshotSupplier.get();
        MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot diagnostics =
                diagnosticsSupplier == null ? null : diagnosticsSupplier.get();
        long observedLearningSamples = observedLearningSamples(diagnostics);
        boolean learningSignalsActive = observedLearningSamples >= minLearningSamplesForHealth;
        boolean critical = snapshot.namespaceIsolationViolations() > maxNamespaceIsolationViolations
                || snapshot.checkpointRecoverySuccessRate() < minCheckpointRecoverySuccessRate
                || snapshot.persistenceSuccessRate() < minPersistenceSuccessRate
                || (learningSignalsActive && snapshot.baselineRelativeLift() < minBaselineRelativeLift)
                || snapshot.regretRate() > maxEvictionRegretRate;
        boolean healthy = !critical
                && snapshot.evictionLogCoverage() >= minEvictionLogCoverage
                && snapshot.storeLatencyP99Ms() <= maxStoreLatencyP99Ms
                && snapshot.recallLatencyP99Ms() <= maxRecallLatencyP99Ms
                && (!learningSignalsActive || snapshot.shadowRelativeLift() >= minShadowRelativeLift)
                && (!learningSignalsActive || snapshot.baselineLiftSustainedRatio() >= minBaselineSustainedRatio);
        List<HealthSummaryItem> summary = buildSummary(snapshot, diagnostics, healthy, learningSignalsActive);
        Status status = critical ? Status.DOWN : (healthy ? Status.UP : DEGRADED);
        if (stateLogger != null) {
            stateLogger.observe(status, summary, snapshot, diagnostics);
        }

        Health.Builder builder = Status.UP.equals(status)
                ? Health.up()
                : Status.DOWN.equals(status) ? Health.down() : Health.status(DEGRADED);
        builder
                .withDetail("status", status.getCode())
                .withDetail("evictionLogCoverage", snapshot.evictionLogCoverage())
                .withDetail("regretRate", snapshot.regretRate())
                .withDetail("namespaceIsolationViolations", snapshot.namespaceIsolationViolations())
                .withDetail("recoverySuccessRate", snapshot.recoverySuccessRate())
                .withDetail("checkpointRecoverySuccessRate", snapshot.checkpointRecoverySuccessRate())
                .withDetail("persistenceSuccessRate", snapshot.persistenceSuccessRate())
                .withDetail("durabilitySuccessRate", snapshot.durabilitySuccessRate())
                .withDetail("storeLatencyP99Ms", snapshot.storeLatencyP99Ms())
                .withDetail("recallLatencyP99Ms", snapshot.recallLatencyP99Ms())
                .withDetail("shadowRelativeLift", snapshot.shadowRelativeLift())
                .withDetail("baselineRelativeLift", snapshot.baselineRelativeLift())
                .withDetail("baselineLiftSustainedRatio", snapshot.baselineLiftSustainedRatio())
                .withDetail("learningEvaluationActive", learningSignalsActive)
                .withDetail("learningSampleCount", observedLearningSamples)
                .withDetail("dictionaryVersion", MemoryHealthSignalCatalog.DICTIONARY_VERSION)
                .withDetail("summary", summary);
        if (diagnostics != null) {
            builder.withDetail("diagnosticWarnings", diagnostics.warnings());
            builder.withDetail("diagnosticSignals", diagnostics.signals());
            builder.withDetail("pagingAssignment", diagnostics.paging().assignment());
            builder.withDetail("prefetchStrategies", diagnostics.paging().prefetchStrategies());
            builder.withDetail("regretModes", diagnostics.regret().modes());
            builder.withDetail("learningScenarios", diagnostics.learning());
        }
        return builder.build();
    }

    private List<HealthSummaryItem> buildSummary(
            MemorySloTracker.SloSnapshot snapshot,
            MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot diagnostics,
            boolean healthy,
            boolean learningSignalsActive) {
        List<RankedSummaryItem> items = new ArrayList<>();
        addSummaryIf(items,
                snapshot.namespaceIsolationViolations() > maxNamespaceIsolationViolations,
                "critical",
                0,
                MemoryHealthSignalCatalog.NAMESPACE_ISOLATION_VIOLATION,
                "Namespace isolation violations detected: " + snapshot.namespaceIsolationViolations());
        addSummaryIf(items,
                snapshot.checkpointRecoverySuccessRate() < minCheckpointRecoverySuccessRate,
                "critical",
                1,
                MemoryHealthSignalCatalog.CHECKPOINT_RECOVERY_SUCCESS_RATE_LOW,
                "Checkpoint recovery success rate dropped to " + format(snapshot.checkpointRecoverySuccessRate())
                        + " (target " + format(minCheckpointRecoverySuccessRate) + ")");
        addSummaryIf(items,
                snapshot.persistenceSuccessRate() < minPersistenceSuccessRate,
                "critical",
                2,
                MemoryHealthSignalCatalog.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                "Memory persistence success rate dropped to " + format(snapshot.persistenceSuccessRate())
                        + " (target " + format(minPersistenceSuccessRate) + ")");
        addSummaryIf(items,
                learningSignalsActive && snapshot.baselineRelativeLift() < minBaselineRelativeLift,
                "critical",
                3,
                MemoryHealthSignalCatalog.BASELINE_LIFT_LOW,
                "Baseline lift fell to " + format(snapshot.baselineRelativeLift())
                        + " (target " + format(minBaselineRelativeLift) + ")");
        addSummaryIf(items,
                snapshot.regretRate() > maxEvictionRegretRate,
                "critical",
                4,
                MemoryHealthSignalCatalog.EVICTION_REGRET_HIGH,
                "Eviction regret rate is " + format(snapshot.regretRate())
                        + " (limit " + format(maxEvictionRegretRate) + ")");
        addSummaryIf(items,
                snapshot.storeLatencyP99Ms() > maxStoreLatencyP99Ms,
                "warning",
                5,
                MemoryHealthSignalCatalog.STORE_LATENCY_P99_HIGH,
                "Store p99 latency is " + format(snapshot.storeLatencyP99Ms())
                        + "ms (limit " + format(maxStoreLatencyP99Ms) + "ms)");
        addSummaryIf(items,
                snapshot.recallLatencyP99Ms() > maxRecallLatencyP99Ms,
                "warning",
                6,
                MemoryHealthSignalCatalog.RECALL_LATENCY_P99_HIGH,
                "Recall p99 latency is " + format(snapshot.recallLatencyP99Ms())
                        + "ms (limit " + format(maxRecallLatencyP99Ms) + "ms)");
        addSummaryIf(items,
                learningSignalsActive && snapshot.shadowRelativeLift() < minShadowRelativeLift,
                "warning",
                7,
                MemoryHealthSignalCatalog.SHADOW_LIFT_REGRESSION,
                "Shadow lift is " + format(snapshot.shadowRelativeLift())
                        + " (target " + format(minShadowRelativeLift) + ")");
        addSummaryIf(items,
                learningSignalsActive && snapshot.baselineLiftSustainedRatio() < minBaselineSustainedRatio,
                "warning",
                8,
                MemoryHealthSignalCatalog.BASELINE_LIFT_NOT_SUSTAINED,
                "Baseline sustained ratio is " + format(snapshot.baselineLiftSustainedRatio())
                        + " (target " + format(minBaselineSustainedRatio) + ")");
        addSummaryIf(items,
                snapshot.evictionLogCoverage() < minEvictionLogCoverage,
                "warning",
                9,
                MemoryHealthSignalCatalog.EVICTION_LOG_COVERAGE_LOW,
                "Eviction log coverage dropped to " + format(snapshot.evictionLogCoverage())
                        + " (target " + format(minEvictionLogCoverage) + ")");

        if (diagnostics != null) {
            List<MemoryDiagnosticSignal> diagnosticSignals = diagnostics.signals();
            if (diagnosticSignals != null && !diagnosticSignals.isEmpty()) {
                for (int i = 0; i < Math.min(3, diagnosticSignals.size()); i++) {
                    MemoryDiagnosticSignal diagnosticSignal = diagnosticSignals.get(i);
                    MemoryHealthSignalCatalog.Signal signal = MemoryHealthSignalCatalog.fromDiagnosticSignal(diagnosticSignal)
                            .orElse(MemoryHealthSignalCatalog.DIAGNOSTIC_WARNING);
                    items.add(new RankedSummaryItem(
                            20 + i,
                            new HealthSummaryItem(
                                    diagnosticSignal.severity(),
                                    signal.code(),
                                    signal.alertName(),
                                    diagnosticSignal.message(),
                                    signal.runbook())));
                }
            } else {
                List<String> warnings = diagnostics.warnings();
                for (int i = 0; i < Math.min(3, warnings.size()); i++) {
                    items.add(new RankedSummaryItem(
                            20 + i,
                            new HealthSummaryItem(
                                    "warning",
                                    MemoryHealthSignalCatalog.DIAGNOSTIC_WARNING.code(),
                                    MemoryHealthSignalCatalog.DIAGNOSTIC_WARNING.alertName(),
                                    warnings.get(i),
                                    MemoryHealthSignalCatalog.DIAGNOSTIC_WARNING.runbook())));
                }
            }
        }

        if (items.isEmpty() && healthy) {
            return List.of(new HealthSummaryItem(
                    "info",
                    MemoryHealthSignalCatalog.HEALTHY.code(),
                    MemoryHealthSignalCatalog.HEALTHY.alertName(),
                    "Memory SLOs and diagnostics are healthy.",
                    MemoryHealthSignalCatalog.HEALTHY.runbook()));
        }

        return items.stream()
                .sorted(Comparator
                        .comparingInt(RankedSummaryItem::rank)
                        .thenComparing(item -> item.item().code()))
                .map(RankedSummaryItem::item)
                .distinct()
                .limit(3)
                .toList();
    }

    private void addSummaryIf(
            List<RankedSummaryItem> items,
            boolean condition,
            String severity,
            int rank,
            MemoryHealthSignalCatalog.Signal signal,
            String message) {
        if (condition) {
            items.add(new RankedSummaryItem(
                    rank,
                    new HealthSummaryItem(severity, signal.code(), signal.alertName(), message, signal.runbook())));
        }
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private long observedLearningSamples(MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot diagnostics) {
        if (diagnostics == null || diagnostics.learning() == null || diagnostics.learning().isEmpty()) {
            return 0L;
        }
        return diagnostics.learning().stream()
                .mapToLong(MemoryDiagnosticsCollector.LearningScenarioDiagnostic::sampleCount)
                .max()
                .orElse(0L);
    }

    public record HealthSummaryItem(String severity, String code, String alertName, String message, String runbook) {
    }

    private record RankedSummaryItem(int rank, HealthSummaryItem item) {
    }
}
