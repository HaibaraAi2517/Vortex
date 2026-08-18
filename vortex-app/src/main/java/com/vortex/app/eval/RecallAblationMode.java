package com.vortex.app.eval;

import com.vortex.common.dto.RecallRankingStrategy;
import com.vortex.common.dto.RetrievalMode;
import com.vortex.common.dto.RerankerType;

public enum RecallAblationMode {
    KEYWORD_ONLY("KeywordOnly", RetrievalMode.KEYWORD_ONLY, false, RerankerType.NONE),
    VECTOR_ONLY("VectorOnly", RetrievalMode.VECTOR_ONLY, false, RerankerType.NONE),
    VECTOR_RERANK(
            "Vector+Rerank",
            RetrievalMode.VECTOR_ONLY,
            true,
            RerankerType.LINEAR_SCORE_FUSION),
    VECTOR_CROSS_ENCODER(
            "Vector+CrossEncoderReranker",
            RetrievalMode.VECTOR_ONLY,
            true,
            RerankerType.CROSS_ENCODER),
    HYBRID("Hybrid", RetrievalMode.HYBRID, false, RerankerType.NONE),
    HYBRID_RERANK(
            "Hybrid+Rerank",
            RetrievalMode.HYBRID,
            true,
            RerankerType.LINEAR_SCORE_FUSION),
    HYBRID_RRF(
            "Hybrid+RRF",
            RetrievalMode.HYBRID,
            false,
            RerankerType.NONE,
            RecallRankingStrategy.RRF),
    HYBRID_RRF_MMR(
            "Hybrid+RRF+MMR",
            RetrievalMode.HYBRID,
            false,
            RerankerType.NONE,
            RecallRankingStrategy.RRF_MMR),
    HYBRID_CROSS_ENCODER(
            "Hybrid+CrossEncoderReranker",
            RetrievalMode.HYBRID,
            true,
            RerankerType.CROSS_ENCODER);

    private final String reportName;
    private final RetrievalMode retrievalMode;
    private final boolean rerankEnabled;
    private final RerankerType rerankerType;
    private final RecallRankingStrategy rankingStrategy;

    RecallAblationMode(
            String reportName,
            RetrievalMode retrievalMode,
            boolean rerankEnabled,
            RerankerType rerankerType) {
        this(
                reportName,
                retrievalMode,
                rerankEnabled,
                rerankerType,
                RecallRankingStrategy.LEGACY);
    }

    RecallAblationMode(
            String reportName,
            RetrievalMode retrievalMode,
            boolean rerankEnabled,
            RerankerType rerankerType,
            RecallRankingStrategy rankingStrategy) {
        this.reportName = reportName;
        this.retrievalMode = retrievalMode;
        this.rerankEnabled = rerankEnabled;
        this.rerankerType = rerankerType;
        this.rankingStrategy = rankingStrategy;
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

    public RerankerType rerankerType() {
        return rerankerType;
    }

    public RecallRankingStrategy rankingStrategy() {
        return rankingStrategy;
    }
}
