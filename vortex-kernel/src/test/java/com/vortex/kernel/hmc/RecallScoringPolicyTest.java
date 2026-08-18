package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RecallScoringPolicyTest {

    @Test
    void memoryPriorCannotContributeMoreThanTenPercent() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecallAccessFrequencyTracker tracker = new RecallAccessFrequencyTracker(clock::get, 1_000L);
        RecallScoringPolicy policy = new RecallScoringPolicy(tracker, clock::get);
        MemoryFragment fragment = fragment("prior", 1.0d, clock.get());
        for (int index = 0; index < 100; index++) {
            tracker.recordRecall(fragment.getId());
        }

        RecallScoringPolicy.ScoreBreakdown breakdown = policy.scoreV2(fragment, 0.0d);

        assertThat(breakdown.utilityPrior()).isBetween(0.0d, 1.0d);
        assertThat(breakdown.totalScore()).isLessThanOrEqualTo(RecallScoringPolicy.MEMORY_PRIOR_WEIGHT);
    }

    @Test
    void relevanceRemainsTheDominantV2Signal() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecallAccessFrequencyTracker tracker = new RecallAccessFrequencyTracker(clock::get, 1_000L);
        RecallScoringPolicy policy = new RecallScoringPolicy(tracker, clock::get);
        MemoryFragment relevant = fragment("relevant", 0.0d, 0L);
        MemoryFragment popular = fragment("popular", 1.0d, clock.get());
        for (int index = 0; index < 100; index++) {
            tracker.recordRecall(popular.getId());
        }

        double relevantScore = policy.scoreV2(relevant, 0.80d).totalScore();
        double popularScore = policy.scoreV2(popular, 0.68d).totalScore();

        assertThat(relevantScore).isGreaterThan(popularScore);
    }

    @Test
    void legacyRecallFormulaMatchesPreviousCompositeScore() {
        long now = System.currentTimeMillis();
        RecallScoringPolicy policy = new RecallScoringPolicy(new RecallAccessFrequencyTracker());
        MemoryFragment fragment = fragment("legacy", 0.7d, now);
        fragment.setEmbedding(new float[]{1.0f, 0.0f});
        AdaptiveWeightProfile profile = AdaptiveWeightProfile.builder()
                .alpha(0.3d)
                .beta(0.5d)
                .gamma(0.2d)
                .build();
        RedundancyAnalyzer.RedundancyStats stats =
                new RedundancyAnalyzer.RedundancyStats(0.04d, 0.02d);

        double score = policy.legacyScore(
                fragment,
                new float[]{1.0f, 0.0f},
                profile,
                stats);

        assertThat(score).isCloseTo(0.3d + 0.5d + 0.14d - 0.04d + 0.02d, within(1.0e-5));
    }

    @Test
    void benchmarkIsolationCanClearDecayedRecallFrequency() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecallAccessFrequencyTracker tracker = new RecallAccessFrequencyTracker(clock::get, 1_000L);
        tracker.recordRecall("fragment");

        assertThat(tracker.score("fragment")).isPositive();

        tracker.removeAll(List.of("fragment"));

        assertThat(tracker.score("fragment")).isZero();
    }

    private MemoryFragment fragment(String id, double importance, long lastAccessTime) {
        return MemoryFragment.builder()
                .id(id)
                .content(id)
                .importance(importance)
                .lastAccessTime(lastAccessTime)
                .build();
    }
}
