package com.vortex.app.eval;

public enum LlmMemoryEvalMode {
    BASELINE_NO_MEMORY("Baseline-NoMemory", false, false),
    VORTEX_MEMORY("Vortex-Memory", true, false),
    VORTEX_RECOVERED_MEMORY("Vortex-RecoveredMemory", true, true);

    private final String reportName;
    private final boolean usesMemory;
    private final boolean requiresEvictionRecovery;

    LlmMemoryEvalMode(String reportName, boolean usesMemory, boolean requiresEvictionRecovery) {
        this.reportName = reportName;
        this.usesMemory = usesMemory;
        this.requiresEvictionRecovery = requiresEvictionRecovery;
    }

    public String reportName() {
        return reportName;
    }

    public boolean usesMemory() {
        return usesMemory;
    }

    public boolean requiresEvictionRecovery() {
        return requiresEvictionRecovery;
    }
}
