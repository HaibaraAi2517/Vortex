package com.vortex.app.eval;

import com.vortex.common.dto.RetrievalMode;

public enum LlmMemoryEvalMode {
    BASELINE_NO_MEMORY("Baseline-NoMemory", false, false, null),
    VORTEX_VECTOR_ONLY("Vortex-VectorOnly", true, false, RetrievalMode.VECTOR_ONLY),
    VORTEX_MEMORY("Vortex-Memory", true, false, RetrievalMode.HYBRID),
    VORTEX_RECOVERED_MEMORY("Vortex-RecoveredMemory", true, true, RetrievalMode.HYBRID);

    private final String reportName;
    private final boolean usesMemory;
    private final boolean requiresEvictionRecovery;
    private final RetrievalMode retrievalMode;

    LlmMemoryEvalMode(String reportName, boolean usesMemory, boolean requiresEvictionRecovery, RetrievalMode retrievalMode) {
        this.reportName = reportName;
        this.usesMemory = usesMemory;
        this.requiresEvictionRecovery = requiresEvictionRecovery;
        this.retrievalMode = retrievalMode;
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

    public RetrievalMode retrievalMode() {
        return retrievalMode;
    }
}
