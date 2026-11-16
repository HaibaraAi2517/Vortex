package com.vortex.storage.api;

import com.vortex.common.model.MemoryFragment;
import java.util.List;

/** Full-corpus lexical search, independent of L1 residency and vector candidate limits. */
public interface IndexedMemoryStore {
    List<KeywordHit> searchKeywords(String query, String namespace, List<String> requiredTags, int limit);

    List<MemoryFragment> searchVectors(float[] embedding, String namespace, List<String> requiredTags, int limit);

    /** Checks authoritative lifecycle state, including expired/replaced facts cached in L1. */
    boolean isRecallable(String fragmentId);

    record KeywordHit(MemoryFragment fragment, double score) {}
}
