package com.vortex.common.dto;

/** Identifies the implementation behind an enabled recall rerank stage. */
public enum RerankerType {
    NONE,
    LINEAR_SCORE_FUSION,
    CROSS_ENCODER
}
