package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;

import java.util.function.LongSupplier;

/** Recall-only scoring policy, deliberately independent from eviction scoring. */
final class RecallScoringPolicy {

    static final double RELEVANCE_WEIGHT = 0.90d;
    static final double MEMORY_PRIOR_WEIGHT = 0.10d;

    private static final long ONE_DAY_MILLIS = 86_400_000L;
    private static final double IMPORTANCE_PRIOR_WEIGHT = 0.50d;
    private static final double FRESHNESS_PRIOR_WEIGHT = 0.30d;
    private static final double ACCESS_FREQUENCY_PRIOR_WEIGHT = 0.20d;

    private final RecallAccessFrequencyTracker accessFrequencyTracker;
    private final LongSupplier clock;

    RecallScoringPolicy(RecallAccessFrequencyTracker accessFrequencyTracker) {
        this(accessFrequencyTracker, System::currentTimeMillis);
    }

    RecallScoringPolicy(
            RecallAccessFrequencyTracker accessFrequencyTracker,
            LongSupplier clock) {
        this.accessFrequencyTracker = accessFrequencyTracker;
        this.clock = clock;
    }

    double legacyScore(
            MemoryFragment fragment,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile,
            RedundancyAnalyzer.RedundancyStats redundancyStats) {
        double recency = freshness(fragment);
        double similarity = fragment.similarityTo(queryEmbedding);
        double importance = fragment.getImportance();
        return profile.getAlpha() * recency
                + profile.getBeta() * similarity
                + profile.getGamma() * importance
                - redundancyStats.redundancyPenalty()
                + redundancyStats.noveltyBonus();
    }

    double semanticRelevance(MemoryFragment fragment, float[] queryEmbedding) {
        return clamp(fragment.similarityTo(queryEmbedding));
    }

    ScoreBreakdown scoreV2(MemoryFragment fragment, double relevance) {
        double boundedRelevance = clamp(relevance);
        double importance = clamp(fragment.getImportance());
        double freshness = freshness(fragment);
        double accessFrequency = clamp(accessFrequencyTracker.score(fragment.getId()));
        double utilityPrior = IMPORTANCE_PRIOR_WEIGHT * importance
                + FRESHNESS_PRIOR_WEIGHT * freshness
                + ACCESS_FREQUENCY_PRIOR_WEIGHT * accessFrequency;
        double totalScore = RELEVANCE_WEIGHT * boundedRelevance
                + MEMORY_PRIOR_WEIGHT * utilityPrior;
        return new ScoreBreakdown(
                boundedRelevance,
                importance,
                freshness,
                accessFrequency,
                utilityPrior,
                totalScore);
    }

    private double freshness(MemoryFragment fragment) {
        long ageMillis = Math.max(0L, clock.getAsLong() - fragment.getLastAccessTime());
        double ageDays = (double) ageMillis / ONE_DAY_MILLIS;
        return 1.0d / (1.0d + Math.log1p(ageDays));
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    record ScoreBreakdown(
            double relevance,
            double importance,
            double freshness,
            double decayedAccessFrequency,
            double utilityPrior,
            double totalScore) {
    }
}
