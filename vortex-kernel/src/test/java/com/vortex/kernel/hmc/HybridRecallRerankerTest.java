package com.vortex.kernel.hmc;

import com.vortex.common.dto.RerankEffectStatus;
import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRecallRerankerTest {

    private final HybridRecallReranker reranker = new HybridRecallReranker();

    @Test
    void keywordVariationMakesRerankEffectIdentifiableWithoutChangingOrder() {
        Map<String, MemoryFragment> candidates = candidates();
        HybridRecallReranker.RerankResult result = reranker.rerankWithDiagnostics(
                candidates,
                Map.of("semantic", 0.90, "keyword", 0.80),
                Map.of("semantic", 0.0, "keyword", 1.0),
                true,
                List.of("longmemeval", "public-dataset"),
                1);

        assertThat(result.candidates()).extracting(candidate -> candidate.fragment().getId())
                .containsExactly("keyword", "semantic");
        assertThat(result.analysis().keywordScoreDistinctCount()).isEqualTo(2);
        assertThat(result.analysis().changedPositionCount()).isZero();
        assertThat(result.analysis().effectStatus()).isEqualTo(RerankEffectStatus.IDENTIFIABLE);
    }

    @Test
    void temporalReasoningProfilePrioritizesSemanticSignalOverKeywordNoise() {
        Map<String, MemoryFragment> candidates = candidates();
        List<HybridRecallReranker.HybridCandidate> ranked = reranker.rerank(
                candidates,
                Map.of("semantic", 0.90, "keyword", 0.80),
                Map.of("semantic", 0.0, "keyword", 1.0),
                true,
                List.of("longmemeval", "category-temporal-reasoning"));

        assertThat(ranked).extracting(candidate -> candidate.fragment().getId())
                .containsExactly("semantic", "keyword");
    }

    @Test
    void vectorOnlyWithConstantImportancePreservesSemanticOrder() {
        Map<String, MemoryFragment> candidates = new LinkedHashMap<>();
        candidates.put("high", candidate("high", 0.5));
        candidates.put("middle", candidate("middle", 0.5));
        candidates.put("non-positive", candidate("non-positive", 0.5));

        HybridRecallReranker.RerankResult result = reranker.rerankWithDiagnostics(
                candidates,
                Map.of("high", 0.90, "middle", 0.40, "non-positive", -0.10),
                Map.of("high", 0.0, "middle", 1.0, "non-positive", 2.0),
                false,
                List.of(),
                2);

        assertThat(result.candidates()).extracting(candidate -> candidate.fragment().getId())
                .containsExactly("high", "middle", "non-positive");
        assertThat(result.analysis().inputCandidateCount()).isEqualTo(3);
        assertThat(result.analysis().outputCandidateCount()).isEqualTo(3);
        assertThat(result.analysis().changedPositionCount()).isZero();
        assertThat(result.analysis().topKMembershipChangedCount()).isZero();
        assertThat(result.analysis().semanticScoreDistinctCount()).isEqualTo(3);
        assertThat(result.analysis().keywordScoreDistinctCount()).isEqualTo(1);
        assertThat(result.analysis().importanceDistinctCount()).isEqualTo(1);
        assertThat(result.analysis().effectStatus()).isEqualTo(RerankEffectStatus.NON_IDENTIFIABLE);
    }

    @Test
    void vectorOnlyWithVaryingImportanceCanChangeSemanticOrder() {
        Map<String, MemoryFragment> candidates = new LinkedHashMap<>();
        candidates.put("semantic-leader", candidate("semantic-leader", 0.0));
        candidates.put("important-nearby", candidate("important-nearby", 1.0));

        HybridRecallReranker.RerankResult result = reranker.rerankWithDiagnostics(
                candidates,
                Map.of("semantic-leader", 0.90, "important-nearby", 0.89),
                Map.of(),
                false,
                List.of(),
                1);

        assertThat(result.candidates()).extracting(candidate -> candidate.fragment().getId())
                .containsExactly("important-nearby", "semantic-leader");
        assertThat(result.analysis().changedPositionCount()).isEqualTo(2);
        assertThat(result.analysis().topKMembershipChangedCount()).isEqualTo(2);
        assertThat(result.analysis().importanceDistinctCount()).isEqualTo(2);
        assertThat(result.analysis().effectStatus()).isEqualTo(RerankEffectStatus.ORDER_CHANGED);
    }

    @Test
    void vectorOnlyWithVaryingAlignedImportanceIsIdentifiableWithoutChangingOrder() {
        Map<String, MemoryFragment> candidates = new LinkedHashMap<>();
        candidates.put("high", candidate("high", 1.0));
        candidates.put("low", candidate("low", 0.0));

        HybridRecallReranker.RerankResult result = reranker.rerankWithDiagnostics(
                candidates,
                Map.of("high", 0.90, "low", 0.10),
                Map.of(),
                false,
                List.of(),
                1);

        assertThat(result.candidates()).extracting(candidate -> candidate.fragment().getId())
                .containsExactly("high", "low");
        assertThat(result.analysis().changedPositionCount()).isZero();
        assertThat(result.analysis().importanceDistinctCount()).isEqualTo(2);
        assertThat(result.analysis().effectStatus()).isEqualTo(RerankEffectStatus.IDENTIFIABLE);
    }

    @Test
    void tiedSemanticScoresWithConstantImportanceAreNonIdentifiable() {
        Map<String, MemoryFragment> candidates = new LinkedHashMap<>();
        candidates.put("first", candidate("first", 0.5));
        candidates.put("second", candidate("second", 0.5));

        HybridRecallReranker.RerankResult result = reranker.rerankWithDiagnostics(
                candidates,
                Map.of("first", 0.75, "second", 0.75),
                Map.of(),
                false,
                List.of(),
                1);

        assertThat(result.candidates()).extracting(candidate -> candidate.fragment().getId())
                .containsExactly("first", "second");
        assertThat(result.analysis().semanticScoreDistinctCount()).isEqualTo(1);
        assertThat(result.analysis().changedPositionCount()).isZero();
        assertThat(result.analysis().effectStatus()).isEqualTo(RerankEffectStatus.NON_IDENTIFIABLE);
    }

    @Test
    void emptyCandidatesAreNotExecuted() {
        HybridRecallReranker.RerankResult result = reranker.rerankWithDiagnostics(
                Map.of(),
                Map.of(),
                Map.of(),
                true,
                List.of(),
                5);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.analysis().inputCandidateCount()).isZero();
        assertThat(result.analysis().outputCandidateCount()).isZero();
        assertThat(result.analysis().effectStatus()).isEqualTo(RerankEffectStatus.NOT_EXECUTED);
    }

    private Map<String, MemoryFragment> candidates() {
        Map<String, MemoryFragment> candidates = new LinkedHashMap<>();
        candidates.put("semantic", candidate("semantic", 0.5));
        candidates.put("keyword", candidate("keyword", 0.5));
        return candidates;
    }

    private MemoryFragment candidate(String id, double importance) {
        return MemoryFragment.builder()
                .id(id)
                .content("Recall candidate " + id)
                .importance(importance)
                .build();
    }
}
