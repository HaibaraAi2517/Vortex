package com.vortex.common.health;

public final class MemoryHealthCodes {

    public static final String EVICTION_LOG_COVERAGE_LOW = "eviction_log_coverage_low";
    public static final String EVICTION_REGRET_HIGH = "eviction_regret_high";
    public static final String NAMESPACE_ISOLATION_VIOLATION = "namespace_isolation_violation";
    public static final String CHECKPOINT_RECOVERY_SUCCESS_RATE_LOW = "checkpoint_recovery_success_rate_low";
    public static final String MEMORY_PERSISTENCE_SUCCESS_RATE_LOW = "memory_persistence_success_rate_low";
    @Deprecated(forRemoval = false)
    public static final String RECOVERY_SUCCESS_RATE_LOW = "recovery_success_rate_low";
    public static final String STORE_LATENCY_P99_HIGH = "store_latency_p99_high";
    public static final String RECALL_LATENCY_P99_HIGH = "recall_latency_p99_high";
    public static final String SHADOW_LIFT_REGRESSION = "shadow_lift_regression";
    public static final String BASELINE_LIFT_LOW = "baseline_lift_low";
    public static final String BASELINE_LIFT_NOT_SUSTAINED = "baseline_lift_not_sustained";
    public static final String PREFETCH_STRATEGY_DEGRADED = "prefetch_strategy_degraded";
    public static final String EVICTION_REGRET_MODE_HIGH = "eviction_regret_mode_high";
    public static final String PAGING_DRIFT_HIGH = "paging_drift_high";
    public static final String LEARNING_REGRESSION = "learning_regression";
    public static final String DIAGNOSTIC_WARNING = "diagnostic_warning";
    public static final String HEALTHY = "healthy";

    private MemoryHealthCodes() {
    }
}
