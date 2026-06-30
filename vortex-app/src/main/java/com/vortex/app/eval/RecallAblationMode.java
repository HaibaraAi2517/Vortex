package com.vortex.app.eval;

import com.vortex.common.dto.RetrievalMode;

public enum RecallAblationMode {
    KEYWORD_ONLY("KeywordOnly", RetrievalMode.KEYWORD_ONLY, false),
    VECTOR_ONLY("VectorOnly", RetrievalMode.VECTOR_ONLY, false),
    VECTOR_RERANK("Vector+Rerank", RetrievalMode.VECTOR_ONLY, true),
    HYBRID("Hybrid", RetrievalMode.HYBRID, false),
    HYBRID_RERANK("Hybrid+Rerank", RetrievalMode.HYBRID, true);

    private final String reportName;
    private final RetrievalMode retrievalMode;
    private final boolean rerankEnabled;

    RecallAblationMode(String reportName, RetrievalMode retrievalMode, boolean rerankEnabled) {
        this.reportName = reportName;
        this.retrievalMode = retrievalMode;
        this.rerankEnabled = rerankEnabled;
    }

    public String reportName() {
        return reportName;
    }

    public RetrievalMode retrievalMode() {
        return retrievalMode;
    }

    public boolean rerankEnabled() {
        return rerankEnabled;
    }
}