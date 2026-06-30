package com.vortex.common.dto;

/**
 * Recall strategy used by the memory retrieval pipeline.
 */
public enum RetrievalMode {
    HYBRID,
    VECTOR_ONLY,
    KEYWORD_ONLY
}
