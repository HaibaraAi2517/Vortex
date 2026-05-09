package com.vortex.common.dto;

import com.vortex.common.model.MemoryFragment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Response DTO for a semantic memory recall operation. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecallResult {

    private List<ScoredFragment> fragments;

    /** Total tokens consumed by the returned fragments. */
    private int totalTokens;

    /** Trace info: where each fragment was found (L1 / L2 / L3). */
    private List<String> sourceTrace;

    /** Session id used to report answer-grounded feedback later. */
    private String recallSessionId;

    /** Active profile name used to serve this recall. */
    private String activeProfileName;

    /** Shadow profile name evaluated alongside this recall. */
    private String shadowProfileName;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoredFragment {
        private MemoryFragment fragment;
        /** Cosine similarity score [0.0, 1.0]. */
        private double score;
        /** Storage tier where this fragment was found: L1, L2, or L3. */
        private String tier;
    }
}
