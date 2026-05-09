package com.vortex.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryFragmentTest {

    @Test
    void computeEvictionScoreKeepsDistinguishingOlderFragmentsAfterOneDay() {
        MemoryFragment twoDaysOld = MemoryFragment.builder()
                .id("two-days")
                .content("two-days")
                .tokenCount(10)
                .importance(0.0)
                .lastAccessTime(System.currentTimeMillis() - 2 * 86_400_000L)
                .build();
        MemoryFragment twoWeeksOld = MemoryFragment.builder()
                .id("two-weeks")
                .content("two-weeks")
                .tokenCount(10)
                .importance(0.0)
                .lastAccessTime(System.currentTimeMillis() - 14 * 86_400_000L)
                .build();

        double twoDaysScore = twoDaysOld.computeEvictionScore(null, 1.0, 0.0, 0.0, 0.0, 0.0);
        double twoWeeksScore = twoWeeksOld.computeEvictionScore(null, 1.0, 0.0, 0.0, 0.0, 0.0);

        assertThat(twoDaysScore).isGreaterThan(twoWeeksScore);
        assertThat(twoWeeksScore).isGreaterThan(0.0);
    }

    @Test
    void reinforceImportanceOnRecallRaisesImportanceWithoutExceedingOne() {
        MemoryFragment fragment = MemoryFragment.builder()
                .id("importance")
                .content("importance")
                .tokenCount(10)
                .importance(0.5)
                .build();

        fragment.reinforceImportanceOnRecall();

        assertThat(fragment.getImportance()).isGreaterThan(0.5).isLessThanOrEqualTo(1.0);
    }

    @Test
    void clearExpiredPinRemovesStalePinState() {
        MemoryFragment fragment = MemoryFragment.builder()
                .id("expired-pin")
                .content("expired-pin")
                .tokenCount(10)
                .pinnedUntil(System.currentTimeMillis() - 1_000L)
                .build();

        boolean cleared = fragment.clearExpiredPin();

        assertThat(cleared).isTrue();
        assertThat(fragment.getPinnedUntil()).isNull();
        assertThat(fragment.isPinned()).isFalse();
    }

    @Test
    void redundancySimilarityUsesAvailableL2Embeddings() {
        MemoryFragment left = MemoryFragment.builder()
                .id("left")
                .content("left")
                .tokenCount(10)
                .embedding(new float[]{1.0f, 0.0f})
                .l2Embedding(new float[]{0.0f, 1.0f})
                .build();
        MemoryFragment right = MemoryFragment.builder()
                .id("right")
                .content("right")
                .tokenCount(10)
                .embedding(new float[]{0.0f, 1.0f})
                .l2Embedding(new float[]{0.0f, 1.0f})
                .build();

        double similarity = left.redundancySimilarityTo(right);

        assertThat(similarity).isEqualTo(1.0);
    }
}
