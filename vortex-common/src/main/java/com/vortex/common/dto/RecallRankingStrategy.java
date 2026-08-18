package com.vortex.common.dto;

/** Selects the recall ranking pipeline without changing the retrieval source. */
public enum RecallRankingStrategy {
    /** Compatibility path that preserves the current public ranking behavior. */
    LEGACY,

    /** Reciprocal-rank fusion followed by a bounded memory utility prior. */
    RRF,

    /** RRF ranking followed by maximal marginal relevance selection. */
    RRF_MMR
}
