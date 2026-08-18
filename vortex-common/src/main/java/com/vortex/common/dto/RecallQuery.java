package com.vortex.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Request DTO for a semantic memory recall operation. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecallQuery {

    @NotBlank
    @Size(max = 4_000)
    private String query;

    /** Namespace to search within. */
    @NotBlank
    @Size(max = 128)
    private String namespace;

    /** Maximum number of fragments to return. */
    @Builder.Default
    @Min(1)
    @Max(100)
    private int topK = 5;

    /** Maximum total token budget for the returned fragments. */
    @Builder.Default
    @Min(1)
    @Max(32_768)
    private int tokenBudget = 2048;

    /** Optional tag filters (AND semantics). */
    @Size(max = 32)
    private List<@NotBlank @Size(max = 128) String> tags;

    /** Scenario for adaptive scoring profile selection: coding/chat/search. */
    @Builder.Default
    private MemoryScenario scenario = MemoryScenario.CHAT;

    /** Retrieval pipeline variant. Guarded hybrid is the validated default. */
    @Builder.Default
    private RetrievalMode retrievalMode = RetrievalMode.HYBRID;

    /** Ranking implementation. RRF is the validated default; Legacy remains available for rollback. */
    @Builder.Default
    private RecallRankingStrategy rankingStrategy = RecallRankingStrategy.RRF;

    /** Whether to apply the recall reranker after candidate generation. */
    @Builder.Default
    private boolean rerankEnabled = false;

    /** Reranker implementation to use when reranking is explicitly enabled. */
    @Builder.Default
    private RerankerType rerankerType = RerankerType.LINEAR_SCORE_FUSION;
}
