package com.vortex.common.model;

/**
 * Lifecycle states for a SemanticPage.
 */
public enum PageState {
    /** Page is resident in L1 and available for fast recall. */
    RESIDENT,
    /** Page has been evicted from L1 but metadata is retained in the page table. */
    EVICTED,
    /** Page is currently being loaded from L2/L3 into L1. */
    FAULTING,
    /** Page is being built (initial K-Means clustering or re-organization). */
    BUILDING
}
