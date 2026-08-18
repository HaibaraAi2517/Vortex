package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MmrCandidateSelectorTest {

    private final MmrCandidateSelector selector = new MmrCandidateSelector();

    @Test
    void dynamicallyPenalizesCandidatesSimilarToAlreadySelectedResults() {
        HybridRecallReranker.HybridCandidate leader = candidate(
                "leader",
                1.0d,
                new float[]{1.0f, 0.0f});
        HybridRecallReranker.HybridCandidate duplicate = candidate(
                "duplicate",
                0.99d,
                new float[]{0.999f, 0.01f});
        HybridRecallReranker.HybridCandidate diverse = candidate(
                "diverse",
                0.90d,
                new float[]{0.0f, 1.0f});

        List<HybridRecallReranker.HybridCandidate> selected =
                selector.select(List.of(leader, duplicate, diverse));

        assertThat(selected).extracting(candidate -> candidate.fragment().getId())
                .containsExactly("leader", "diverse", "duplicate");
    }

    @Test
    void preservesRelevanceOrderForCandidatesThatAreNotNearDuplicates() {
        HybridRecallReranker.HybridCandidate leader = candidate(
                "leader",
                1.0d,
                new float[]{1.0f, 0.0f});
        HybridRecallReranker.HybridCandidate related = candidate(
                "related",
                0.99d,
                new float[]{0.94f, 0.341f});
        HybridRecallReranker.HybridCandidate diverse = candidate(
                "diverse",
                0.98d,
                new float[]{0.0f, 1.0f});

        List<HybridRecallReranker.HybridCandidate> selected =
                selector.select(List.of(leader, related, diverse), 2);

        assertThat(selected).extracting(candidate -> candidate.fragment().getId())
                .containsExactly("leader", "related", "diverse");
    }

    private HybridRecallReranker.HybridCandidate candidate(
            String id,
            double score,
            float[] embedding) {
        MemoryFragment fragment = MemoryFragment.builder()
                .id(id)
                .content(id)
                .embedding(embedding)
                .build();
        return new HybridRecallReranker.HybridCandidate(fragment, score, score, 0.0d);
    }
}
