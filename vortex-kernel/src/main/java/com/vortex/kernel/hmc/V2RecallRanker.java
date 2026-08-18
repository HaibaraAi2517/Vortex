package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** RRF and memory-prior ranking stage used by the opt-in V2 strategies. */
final class V2RecallRanker {

    private final RecallScoringPolicy scoringPolicy;
    private final ReciprocalRankFusion reciprocalRankFusion;

    V2RecallRanker(
            RecallScoringPolicy scoringPolicy,
            ReciprocalRankFusion reciprocalRankFusion) {
        this.scoringPolicy = scoringPolicy;
        this.reciprocalRankFusion = reciprocalRankFusion;
    }

    List<HybridRecallReranker.HybridCandidate> rank(
            Map<String, MemoryFragment> candidates,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            boolean keywordEnabled) {
        return rank(
                candidates,
                semanticScores,
                keywordScores,
                keywordEnabled ? 1.0d : 0.0d);
    }

    List<HybridRecallReranker.HybridCandidate> rank(
            Map<String, MemoryFragment> candidates,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            double keywordWeight) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Double> relevanceScores = reciprocalRankFusion.fuse(
                candidates.keySet().stream().toList(),
                semanticScores,
                keywordScores,
                keywordWeight);
        boolean keywordEnabled = keywordWeight > 0.0d;
        return candidates.values().stream()
                .map(fragment -> {
                    double relevance = relevanceScores.getOrDefault(fragment.getId(), 0.0d);
                    RecallScoringPolicy.ScoreBreakdown breakdown = scoringPolicy.scoreV2(fragment, relevance);
                    return new HybridRecallReranker.HybridCandidate(
                            fragment,
                            breakdown.totalScore(),
                            scoreFor(semanticScores, fragment.getId()),
                            keywordEnabled ? scoreFor(keywordScores, fragment.getId()) : 0.0d);
                })
                .sorted(Comparator.comparingDouble(HybridRecallReranker.HybridCandidate::score).reversed())
                .toList();
    }

    private double scoreFor(Map<String, Double> scores, String fragmentId) {
        if (scores == null) {
            return 0.0d;
        }
        Double score = scores.get(fragmentId);
        return score == null ? 0.0d : score;
    }
}
