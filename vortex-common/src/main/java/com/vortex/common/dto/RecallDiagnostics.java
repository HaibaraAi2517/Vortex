package com.vortex.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Structured diagnostics for the semantic recall pipeline. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecallDiagnostics {

    @Builder.Default
    private List<String> requiredTags = List.of();

    private int l1CandidateCount;
    private int l1TagMatchedCount;
    private int l1SelectedCount;
    private int l1TokenBudgetRejectedCount;

    private String retrievalMode;
    private boolean rerankEnabled;
    private int keywordCandidateCount;
    private int keywordAcceptedCount;
    private int keywordDuplicateRejectedCount;
    private int keywordTagRejectedCount;
    private int keywordTokenBudgetRejectedCount;
    private int vectorCandidateCount;
    private int vectorAcceptedCount;
    private int rerankCandidateCount;
    private int rerankInputCandidateCount;
    private int rerankOutputCandidateCount;
    private int rerankChangedPositionCount;
    private int rerankTopKMembershipChangedCount;
    private int rerankPreselectionCandidateCount;
    private int rerankCandidatePoolLimit;
    private String rerankCandidatePoolStrategy;
    private int rerankScoreDistinctCount;
    private long rerankLatencyNanos;
    private String rerankModel;
    private String rerankModelVersion;
    private String rerankModelSha256;
    private int semanticScoreDistinctCount;
    private int keywordScoreDistinctCount;
    private int importanceDistinctCount;

    @Builder.Default
    private RerankerType rerankerType = RerankerType.NONE;

    @Builder.Default
    private RerankEffectStatus rerankEffectStatus = RerankEffectStatus.NOT_EXECUTED;

    private int l2SearchCandidateCount;
    private int l2SearchAcceptedCount;
    private int l2SearchDuplicateRejectedCount;
    private int l2SearchTagRejectedCount;
    private int l2SearchTokenBudgetRejectedCount;

    private int l2NamespaceFallbackCandidateCount;
    private int l2NamespaceFallbackAcceptedCount;
    private int l2NamespaceFallbackDuplicateRejectedCount;
    private int l2NamespaceFallbackTagRejectedCount;
    private int l2NamespaceFallbackTokenBudgetRejectedCount;

    private int findFragmentL1HitCount;
    private int findFragmentL3HitCount;
    private int findFragmentL2HitCount;
    private int findFragmentMissCount;

    private int enrichFragmentTagMatchedCount;
    private int enrichCandidateTagMatchedCount;
    private int enrichL2TagFallbackMatchedCount;
    private int enrichTagRejectedCount;

    private int finalReturnedCount;
    private String emptyRecallReason;
}
