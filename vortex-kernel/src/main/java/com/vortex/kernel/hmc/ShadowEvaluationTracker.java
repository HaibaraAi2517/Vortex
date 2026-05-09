package com.vortex.kernel.hmc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Component
public class ShadowEvaluationTracker {

    private final double promotionThreshold;
    private final Duration promotionWindow;
    private final ConcurrentHashMap<String, ShadowProfileStats> statsByScenario = new ConcurrentHashMap<>();

    public ShadowEvaluationTracker(
            @Value("${vortex.kernel.learning.shadow-promotion-threshold:0.20}") double promotionThreshold,
            @Value("${vortex.kernel.learning.shadow-promotion-window-days:14}") long promotionWindowDays) {
        this.promotionThreshold = promotionThreshold;
        this.promotionWindow = Duration.ofDays(promotionWindowDays);
    }

    public void recordEvaluation(String scenarioKey, List<String> activeRanked, List<String> shadowRanked, Set<String> relevantIds) {
        recordEvaluation(scenarioKey, activeRanked, shadowRanked, activeRanked, relevantIds);
    }

    public void recordEvaluation(
            String scenarioKey,
            List<String> activeRanked,
            List<String> shadowRanked,
            List<String> baselineRanked,
            Set<String> relevantIds) {
        ShadowProfileStats stats = statsByScenario.computeIfAbsent(scenarioKey, ignored -> new ShadowProfileStats());
        double activeNdcg = ndcg(activeRanked, relevantIds);
        double shadowNdcg = ndcg(shadowRanked, relevantIds);
        double baselineNdcg = ndcg(baselineRanked, relevantIds);
        stats.activeTotal.add(activeNdcg);
        stats.shadowTotal.add(shadowNdcg);
        stats.baselineTotal.add(baselineNdcg);
        stats.sampleCount.increment();
        if (shadowNdcg > activeNdcg * (1.0 + promotionThreshold)) {
            if (stats.promotionWindowStart == null) {
                stats.promotionWindowStart = Instant.now();
            }
        } else {
            stats.promotionWindowStart = null;
        }
    }

    public ShadowEvaluationSnapshot snapshot(String scenarioKey) {
        ShadowProfileStats stats = statsByScenario.getOrDefault(scenarioKey, new ShadowProfileStats());
        long samples = stats.sampleCount.sum();
        double activeAvg = samples == 0 ? 0.0 : stats.activeTotal.sum() / samples;
        double shadowAvg = samples == 0 ? 0.0 : stats.shadowTotal.sum() / samples;
        double baselineAvg = samples == 0 ? 0.0 : stats.baselineTotal.sum() / samples;
        double relativeLift = activeAvg == 0.0 ? 0.0 : (shadowAvg - activeAvg) / activeAvg;
        double baselineRelativeLift = baselineAvg == 0.0 ? 0.0 : (activeAvg - baselineAvg) / baselineAvg;
        boolean eligibleForPromotion = stats.promotionWindowStart != null
                && Duration.between(stats.promotionWindowStart, Instant.now()).compareTo(promotionWindow) >= 0
                && relativeLift >= promotionThreshold;
        return new ShadowEvaluationSnapshot(
                activeAvg,
                shadowAvg,
                baselineAvg,
                relativeLift,
                baselineRelativeLift,
                samples,
                eligibleForPromotion,
                stats.promotionWindowStart);
    }

    private double ndcg(List<String> rankedIds, Set<String> relevantIds) {
        if (rankedIds == null || rankedIds.isEmpty() || relevantIds == null || relevantIds.isEmpty()) {
            return 0.0;
        }
        double dcg = 0.0;
        for (int i = 0; i < rankedIds.size(); i++) {
            if (relevantIds.contains(rankedIds.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealHits = Math.min(rankedIds.size(), relevantIds.size());
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static final class ShadowProfileStats {
        private final DoubleAdderLike activeTotal = new DoubleAdderLike();
        private final DoubleAdderLike shadowTotal = new DoubleAdderLike();
        private final DoubleAdderLike baselineTotal = new DoubleAdderLike();
        private final LongAdder sampleCount = new LongAdder();
        private Instant promotionWindowStart;
    }

    public record ShadowEvaluationSnapshot(
            double activeAverageNdcg,
            double shadowAverageNdcg,
            double baselineAverageNdcg,
            double relativeLift,
            double baselineRelativeLift,
            long sampleCount,
            boolean eligibleForPromotion,
            Instant promotionWindowStart) {
    }

    private static final class DoubleAdderLike {
        private double value;

        synchronized void add(double delta) {
            value += delta;
        }

        synchronized double sum() {
            return value;
        }
    }
}
