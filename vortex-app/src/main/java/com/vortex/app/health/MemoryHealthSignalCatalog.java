package com.vortex.app.health;

import com.vortex.common.health.MemoryDiagnosticSignal;
import com.vortex.common.health.MemoryHealthCodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MemoryHealthSignalCatalog {

    public static final String DICTIONARY_VERSION = "memory-health-v2";
    public static final String RUNBOOK_PATH = "ops/runbooks/memory-health-signals.md";
    public static final String MIGRATION_GUIDE_PATH = "ops/runbooks/memory-health-migration.md";

    public static final Signal EVICTION_LOG_COVERAGE_LOW = signal(
            MemoryHealthCodes.EVICTION_LOG_COVERAGE_LOW,
            "warning",
            "slo",
            "coverage",
            "VortexMemoryEvictionLogCoverageLow",
            "Eviction decision logging coverage dropped below target.",
            "Check eviction logger ingestion and verify semantic eviction decisions are still emitted.",
            RUNBOOK_PATH + "#eviction_log_coverage_low");
    public static final Signal EVICTION_REGRET_HIGH = signal(
            MemoryHealthCodes.EVICTION_REGRET_HIGH,
            "critical",
            "slo",
            "eviction",
            "VortexMemoryEvictionRegretHigh",
            "Evicted fragments are being recalled too often, indicating poor victim selection.",
            "Inspect regret modes, protected groups, and recent pinning pressure before tuning eviction weights.",
            RUNBOOK_PATH + "#eviction_regret_high");
    public static final Signal NAMESPACE_ISOLATION_VIOLATION = signal(
            MemoryHealthCodes.NAMESPACE_ISOLATION_VIOLATION,
            "critical",
            "slo",
            "isolation",
            "VortexMemoryNamespaceIsolationViolation",
            "Cross-namespace contamination was detected in the memory pipeline.",
            "Treat as correctness issue: inspect namespace filters and stop broad recalls until isolated.",
            RUNBOOK_PATH + "#namespace_isolation_violation");
    public static final Signal CHECKPOINT_RECOVERY_SUCCESS_RATE_LOW = signal(
            MemoryHealthCodes.CHECKPOINT_RECOVERY_SUCCESS_RATE_LOW,
            "critical",
            "slo",
            "durability",
            "VortexCheckpointRecoverySuccessRateLow",
            "Checkpoint recovery success rate is below target.",
            "Inspect checkpoint chain integrity, WAL replay errors, and cold-store availability.",
            RUNBOOK_PATH + "#checkpoint_recovery_success_rate_low");
    public static final Signal MEMORY_PERSISTENCE_SUCCESS_RATE_LOW = signal(
            MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
            "critical",
            "slo",
            "durability",
            "VortexMemoryPersistenceSuccessRateLow",
            "The memory persistence chain is dropping or permanently failing fragment writes.",
            "Inspect L2/L3 write failures, DLQ backlog growth, and retry exhaustion before widening write traffic.",
            RUNBOOK_PATH + "#memory_persistence_success_rate_low");
    public static final Signal STORE_LATENCY_P99_HIGH = signal(
            MemoryHealthCodes.STORE_LATENCY_P99_HIGH,
            "warning",
            "slo",
            "latency",
            "VortexMemoryStoreLatencyP99High",
            "Store path p99 latency exceeded the configured budget.",
            "Check embedding latency, async persistence backlog, and L1 admission contention.",
            RUNBOOK_PATH + "#store_latency_p99_high");
    public static final Signal RECALL_LATENCY_P99_HIGH = signal(
            MemoryHealthCodes.RECALL_LATENCY_P99_HIGH,
            "warning",
            "slo",
            "latency",
            "VortexMemoryRecallLatencyP99High",
            "Recall path p99 latency exceeded the configured budget.",
            "Inspect L1 hit ratio, L2 search latency, and page-fault amplification during recall.",
            RUNBOOK_PATH + "#recall_latency_p99_high");
    public static final Signal SHADOW_LIFT_REGRESSION = signal(
            MemoryHealthCodes.SHADOW_LIFT_REGRESSION,
            "warning",
            "slo",
            "learning",
            "VortexMemoryShadowLiftRegression",
            "Shadow profile performance regressed versus the active profile.",
            "Inspect feedback quality and learner drift before promoting any updated weights.",
            RUNBOOK_PATH + "#shadow_lift_regression");
    public static final Signal BASELINE_LIFT_LOW = signal(
            MemoryHealthCodes.BASELINE_LIFT_LOW,
            "critical",
            "slo",
            "learning",
            "VortexMemoryBaselineLiftBelowTarget",
            "The active profile no longer beats the baseline by the required margin.",
            "Investigate recent feedback distribution and consider rolling back aggressive learner changes.",
            RUNBOOK_PATH + "#baseline_lift_low");
    public static final Signal BASELINE_LIFT_NOT_SUSTAINED = signal(
            MemoryHealthCodes.BASELINE_LIFT_NOT_SUSTAINED,
            "warning",
            "slo",
            "learning",
            "VortexMemoryBaselineLiftNotSustained",
            "Baseline lift is too inconsistent across recent feedback windows.",
            "Check whether gains are concentrated in one scenario and whether feedback volume is sufficient.",
            RUNBOOK_PATH + "#baseline_lift_not_sustained");
    public static final Signal PREFETCH_STRATEGY_DEGRADED = signal(
            MemoryHealthCodes.PREFETCH_STRATEGY_DEGRADED,
            "warning",
            "diagnostic",
            "prefetch",
            "VortexMemoryPrefetchStrategyDegraded",
            "One or more prefetch strategies are consuming budget without later hits.",
            "Inspect per-strategy hit rate and reduce budgets for degraded strategies before widening recall scope.",
            RUNBOOK_PATH + "#prefetch_strategy_degraded");
    public static final Signal EVICTION_REGRET_MODE_HIGH = signal(
            MemoryHealthCodes.EVICTION_REGRET_MODE_HIGH,
            "warning",
            "diagnostic",
            "eviction",
            "VortexMemoryEvictionRegretModeHigh",
            "A specific eviction mode is showing elevated regret.",
            "Inspect mode-tagged regret metrics and rebalance protected groups or mode-specific heuristics.",
            RUNBOOK_PATH + "#eviction_regret_mode_high");
    public static final Signal PAGING_DRIFT_HIGH = signal(
            MemoryHealthCodes.PAGING_DRIFT_HIGH,
            "warning",
            "diagnostic",
            "paging",
            "VortexMemoryPagingDriftHigh",
            "Semantic page assignment drift suggests incremental placement is losing locality.",
            "Inspect page assignment reuse rate and semantic distance thresholds before rebuilding pages.",
            RUNBOOK_PATH + "#paging_drift_high");
    public static final Signal LEARNING_REGRESSION = signal(
            MemoryHealthCodes.LEARNING_REGRESSION,
            "warning",
            "diagnostic",
            "learning",
            null,
            "Online learning signals indicate regression in at least one scenario.",
            "Inspect scenario-level shadow lift and pending feedback volume before adapting weights further.",
            RUNBOOK_PATH + "#learning_regression");
    public static final Signal DIAGNOSTIC_WARNING = signal(
            MemoryHealthCodes.DIAGNOSTIC_WARNING,
            "warning",
            "diagnostic",
            "general",
            null,
            "A memory diagnostic warning was raised without a dedicated catalog code.",
            "Inspect diagnostic warnings and add a dedicated signal if the condition becomes recurring.",
            RUNBOOK_PATH + "#diagnostic_warning");
    public static final Signal HEALTHY = signal(
            MemoryHealthCodes.HEALTHY,
            "info",
            "status",
            "general",
            null,
            "Memory SLOs and diagnostics are healthy.",
            "No action required.",
            RUNBOOK_PATH + "#healthy");

    private static final List<Signal> SIGNALS = List.of(
            NAMESPACE_ISOLATION_VIOLATION,
            CHECKPOINT_RECOVERY_SUCCESS_RATE_LOW,
            MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
            BASELINE_LIFT_LOW,
            EVICTION_REGRET_HIGH,
            STORE_LATENCY_P99_HIGH,
            RECALL_LATENCY_P99_HIGH,
            SHADOW_LIFT_REGRESSION,
            BASELINE_LIFT_NOT_SUSTAINED,
            EVICTION_LOG_COVERAGE_LOW,
            PREFETCH_STRATEGY_DEGRADED,
            EVICTION_REGRET_MODE_HIGH,
            PAGING_DRIFT_HIGH,
            LEARNING_REGRESSION,
            DIAGNOSTIC_WARNING,
            HEALTHY);

    @SuppressWarnings("deprecation")
    private static final List<CompatibilityNote> COMPATIBILITY_NOTES = List.of(
            new CompatibilityNote(
                    "health_code",
                    MemoryHealthCodes.RECOVERY_SUCCESS_RATE_LOW,
                    MemoryHealthCodes.CHECKPOINT_RECOVERY_SUCCESS_RATE_LOW,
                    "Legacy recovery alert code remains as a temporary alias for checkpoint recovery consumers.",
                    "2026-05-25",
                    "2026-08-31"),
            new CompatibilityNote(
                    "health_detail",
                    "recoverySuccessRate",
                    "checkpointRecoverySuccessRate",
                    "Legacy health detail remains for API consumers that still deserialize the pre-split recovery field.",
                    "2026-05-25",
                    "2026-08-31"),
            new CompatibilityNote(
                    "prometheus_metric",
                    "vortex_hmc_slo_recovery_success_rate",
                    "vortex_hmc_slo_checkpoint_recovery_success_rate",
                    "Legacy Prometheus metric remains for dashboard continuity while alerts move to the checkpoint-specific series.",
                    "2026-05-25",
                    "2026-08-31"));

    private static final Map<String, Signal> SIGNALS_BY_CODE = indexByCode(SIGNALS);

    private MemoryHealthSignalCatalog() {
    }

    public static List<Signal> catalog() {
        return SIGNALS;
    }

    public static List<CompatibilityNote> compatibilityNotes() {
        return COMPATIBILITY_NOTES;
    }

    public static Optional<Signal> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SIGNALS_BY_CODE.get(code));
    }

    public static Optional<Signal> fromDiagnosticSignal(MemoryDiagnosticSignal diagnosticSignal) {
        if (diagnosticSignal == null || diagnosticSignal.code() == null || diagnosticSignal.code().isBlank()) {
            return Optional.empty();
        }
        return findByCode(diagnosticSignal.code()).or(() -> Optional.of(DIAGNOSTIC_WARNING));
    }

    private static Map<String, Signal> indexByCode(List<Signal> signals) {
        Map<String, Signal> indexed = new LinkedHashMap<>();
        for (Signal signal : signals) {
            indexed.put(signal.code(), signal);
        }
        return Map.copyOf(indexed);
    }

    private static Signal signal(
            String code,
            String severity,
            String source,
            String domain,
            String alertName,
            String description,
            String operatorAction,
            String runbook) {
        return new Signal(code, severity, source, domain, alertName, description, operatorAction, runbook);
    }

    public record Signal(
            String code,
            String severity,
            String source,
            String domain,
            String alertName,
            String description,
            String operatorAction,
            String runbook) {
    }

    public record CompatibilityNote(
            String surface,
            String deprecatedKey,
            String replacementKey,
            String rationale,
            String deprecatedOn,
            String removeNoEarlierThan) {
    }
}
