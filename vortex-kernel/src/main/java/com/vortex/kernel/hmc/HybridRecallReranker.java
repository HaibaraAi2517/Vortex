package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Combines semantic and lexical candidates into a single recall ranking.
 */
@Component
public class HybridRecallReranker {

    private static final double SEMANTIC_WEIGHT = 0.70;
    private static final double KEYWORD_WEIGHT = 0.25;
    private static final double IMPORTANCE_WEIGHT = 0.05;

    public List<HybridCandidate> rerank(
            Map<String, MemoryFragment> candidates,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            boolean keywordEnabled) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        double semanticMax = maxScore(semanticScores);
        double keywordMax = maxScore(keywordScores);
        return candidates.values().stream()
                .map(fragment -> {
                    double semanticScore = normalized(semanticScores.get(fragment.getId()), semanticMax);
                    double keywordScore = keywordEnabled
                            ? normalized(keywordScores.get(fragment.getId()), keywordMax)
                            : 0.0;
                    double importanceScore = clamp(fragment.getImportance());
                    double blendedScore = SEMANTIC_WEIGHT * semanticScore
                            + KEYWORD_WEIGHT * keywordScore
                            + IMPORTANCE_WEIGHT * importanceScore;
                    return new HybridCandidate(fragment, blendedScore, semanticScore, keywordScore);
                })
                .sorted(Comparator.comparingDouble(HybridCandidate::score).reversed())
                .toList();
    }

    private double maxScore(Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return 0.0;
        }
        return scores.values().stream()
                .mapToDouble(value -> value == null ? 0.0 : value)
                .max()
                .orElse(0.0);
    }

    private double normalized(Double score, double max) {
        if (score == null || score <= 0.0) {
            return 0.0;
        }
        if (max <= 0.0) {
            return score;
        }
        return Math.min(1.0, score / max);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record HybridCandidate(
            MemoryFragment fragment,
            double score,
            double semanticScore,
            double keywordScore) {
    }
}
