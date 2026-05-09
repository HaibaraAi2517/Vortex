package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticEvictionPolicyTest {

    // alpha=0.3, beta=0.5, gamma=0.2  (sum=1.0)
    private final SemanticEvictionPolicy policy = new SemanticEvictionPolicy(0.3, 0.5, 0.2);

    @Test
    void selectForEviction_returnsLowestScoringFragments() {
        // Fragment A: accessed recently, high importance
        MemoryFragment a = MemoryFragment.builder()
                .id("a").content("important recent").tokenCount(10)
                .importance(0.9).lastAccessTime(System.currentTimeMillis()).build();

        // Fragment B: accessed long ago, low importance
        MemoryFragment b = MemoryFragment.builder()
                .id("b").content("old irrelevant").tokenCount(10)
                .importance(0.1).lastAccessTime(System.currentTimeMillis() - 86_400_000L * 2).build();

        // Fragment C: medium
        MemoryFragment c = MemoryFragment.builder()
                .id("c").content("medium").tokenCount(10)
                .importance(0.5).lastAccessTime(System.currentTimeMillis() - 3_600_000L).build();

        List<MemoryFragment> toEvict = policy.selectForEviction(List.of(a, b, c), null, 10);

        assertThat(toEvict).hasSize(1);
        // B should be evicted first (lowest recency + no semantic boost)
        assertThat(toEvict.get(0).getId()).isEqualTo("b");
    }

    @Test
    void selectForEviction_reachesRequestedTokenRelease() {
        List<MemoryFragment> candidates = List.of(
                MemoryFragment.builder().id("x").content("x").tokenCount(5)
                        .lastAccessTime(System.currentTimeMillis()).build(),
                MemoryFragment.builder().id("y").content("y").tokenCount(5)
                        .lastAccessTime(System.currentTimeMillis() - 1000).build(),
                MemoryFragment.builder().id("z").content("z").tokenCount(5)
                        .lastAccessTime(System.currentTimeMillis() - 2000).build()
        );
        List<MemoryFragment> toEvict = policy.selectForEviction(candidates, null, 10);
        assertThat(toEvict).hasSize(2);
    }

    @Test
    void selectForEviction_prioritizesLowerValuePerTokenDensity() {
        SemanticEvictionPolicy densityPolicy = new SemanticEvictionPolicy(0.0, 0.0, 1.0);
        MemoryFragment tinyLowValue = MemoryFragment.builder()
                .id("tiny")
                .content("tiny")
                .tokenCount(1)
                .importance(0.1)
                .build();
        MemoryFragment largeBetterValue = MemoryFragment.builder()
                .id("large")
                .content("large")
                .tokenCount(100)
                .importance(0.2)
                .build();

        List<MemoryFragment> toEvict = densityPolicy.selectForEviction(
                List.of(tinyLowValue, largeBetterValue), null, 50);

        assertThat(toEvict)
                .extracting(MemoryFragment::getId)
                .containsExactly("large");
    }

    @Test
    void scoreFragment_exposesContributionBreakdown() {
        MemoryFragment fragment = MemoryFragment.builder()
                .id("scored")
                .content("scored")
                .embedding(new float[]{1.0f, 0.0f})
                .tokenCount(10)
                .importance(0.5)
                .lastAccessTime(System.currentTimeMillis())
                .build();

        SemanticEvictionPolicy.EvictionCandidate candidate =
                policy.scoreFragment(fragment, new float[]{1.0f, 0.0f});

        assertThat(candidate.recencyContribution()).isPositive();
        assertThat(candidate.similarityContribution()).isPositive();
        assertThat(candidate.importanceContribution()).isEqualTo(0.1);
        assertThat(candidate.noveltyBonus()).isGreaterThanOrEqualTo(0.0);
        assertThat(candidate.totalScore()).isEqualTo(
                candidate.recencyContribution()
                        + candidate.similarityContribution()
                        + candidate.importanceContribution()
                        - candidate.redundancyPenalty()
                        + candidate.noveltyBonus());
    }

    @Test
    void rankCandidates_skipsPinnedFragmentsAndPenalizesRedundantCopies() {
        MemoryFragment pinned = MemoryFragment.builder()
                .id("pinned")
                .content("pinned")
                .embedding(new float[]{1.0f, 0.0f})
                .tokenCount(10)
                .importance(0.1)
                .build();
        pinned.pinForMillis(60_000L);
        MemoryFragment redundantA = MemoryFragment.builder()
                .id("dup-a")
                .content("dup-a")
                .embedding(new float[]{1.0f, 0.0f})
                .tokenCount(10)
                .importance(0.1)
                .build();
        MemoryFragment redundantB = MemoryFragment.builder()
                .id("dup-b")
                .content("dup-b")
                .embedding(new float[]{1.0f, 0.0f})
                .tokenCount(10)
                .importance(0.1)
                .build();

        List<SemanticEvictionPolicy.EvictionCandidate> ranked = policy.rankCandidates(
                List.of(pinned, redundantA, redundantB),
                new float[]{0.0f, 1.0f});

        assertThat(ranked)
                .extracting(candidate -> candidate.fragment().getId())
                .doesNotContain("pinned");
        assertThat(ranked).allMatch(candidate -> candidate.redundancyPenalty() > 0.0);
    }

    @Test
    void scoreFragmentDoesNotGrantNoveltyBonusWithoutPeers() {
        MemoryFragment fragment = MemoryFragment.builder()
                .id("solo")
                .content("solo")
                .tokenCount(10)
                .importance(0.2)
                .build();

        SemanticEvictionPolicy.EvictionCandidate candidate = policy.scoreFragment(fragment, null);

        assertThat(candidate.noveltyBonus()).isZero();
    }

    @Test
    void rankCandidatesUsesReasoningGroupDensityAndDeduplicatesSelection() {
        SemanticEvictionPolicy densityPolicy = new SemanticEvictionPolicy(0.0, 0.0, 1.0);
        MemoryFragment chainA = MemoryFragment.builder()
                .id("chain-a")
                .content("chain-a")
                .tokenCount(50)
                .importance(0.1)
                .reasoningChainId("chain")
                .build();
        MemoryFragment chainB = MemoryFragment.builder()
                .id("chain-b")
                .content("chain-b")
                .tokenCount(50)
                .importance(0.1)
                .reasoningChainId("chain")
                .build();
        MemoryFragment solo = MemoryFragment.builder()
                .id("solo")
                .content("solo")
                .tokenCount(10)
                .importance(0.15)
                .build();

        List<SemanticEvictionPolicy.EvictionCandidate> selected = densityPolicy.selectDetailedForEviction(
                List.of(chainA, chainB, solo),
                null,
                60);

        assertThat(selected)
                .extracting(candidate -> candidate.fragment().getId())
                .containsExactly("chain-a");
        assertThat(selected.getFirst().groupTokenCount()).isEqualTo(100);
    }
}
