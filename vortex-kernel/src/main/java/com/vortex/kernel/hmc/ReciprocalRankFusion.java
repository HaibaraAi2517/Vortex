package com.vortex.kernel.hmc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rank-based fusion for semantic and lexical candidate lists. */
final class ReciprocalRankFusion {

    private static final double RANK_CONSTANT = 60.0d;
    private static final double VECTOR_WEIGHT = 1.0d;

    Map<String, Double> fuse(
            List<String> candidateIds,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            boolean keywordEnabled) {
        return fuse(
                candidateIds,
                semanticScores,
                keywordScores,
                keywordEnabled ? 1.0d : 0.0d);
    }

    Map<String, Double> fuse(
            List<String> candidateIds,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            double keywordWeight) {
        Map<String, Integer> vectorRanks = ranks(candidateIds, semanticScores);
        double boundedKeywordWeight = Math.max(0.0d, Math.min(1.0d, keywordWeight));
        Map<String, Integer> keywordRanks = boundedKeywordWeight > 0.0d
                ? ranks(candidateIds, keywordScores)
                : Map.of();
        if (vectorRanks.isEmpty()) {
            return normalizedSingleBranch(candidateIds, keywordScores, false);
        }
        if (keywordRanks.isEmpty()) {
            return normalizedSingleBranch(candidateIds, semanticScores, true);
        }
        double maximum = 0.0d;
        maximum += VECTOR_WEIGHT / (RANK_CONSTANT + 1.0d);
        maximum += boundedKeywordWeight / (RANK_CONSTANT + 1.0d);

        Map<String, Double> fused = new LinkedHashMap<>();
        for (String candidateId : candidateIds) {
            double score = contribution(vectorRanks.get(candidateId), VECTOR_WEIGHT)
                    + contribution(keywordRanks.get(candidateId), boundedKeywordWeight);
            fused.put(candidateId, maximum <= 0.0d ? 0.0d : Math.min(1.0d, score / maximum));
        }
        return fused;
    }

    private Map<String, Double> normalizedSingleBranch(
            List<String> candidateIds,
            Map<String, Double> scores,
            boolean alreadyNormalized) {
        double maximum = alreadyNormalized || scores == null
                ? 1.0d
                : scores.values().stream()
                        .filter(value -> value != null && Double.isFinite(value))
                        .mapToDouble(Double::doubleValue)
                        .max()
                        .orElse(0.0d);
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (String candidateId : candidateIds) {
            double score = Math.max(0.0d, scoreFor(scores, candidateId));
            double value = maximum <= 0.0d ? 0.0d : score / maximum;
            normalized.put(candidateId, Math.min(1.0d, value));
        }
        return normalized;
    }

    private Map<String, Integer> ranks(List<String> candidateIds, Map<String, Double> scores) {
        if (candidateIds == null || candidateIds.isEmpty() || scores == null || scores.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> insertionOrder = new HashMap<>();
        for (int index = 0; index < candidateIds.size(); index++) {
            insertionOrder.put(candidateIds.get(index), index);
        }
        List<String> rankedIds = new ArrayList<>();
        for (String candidateId : candidateIds) {
            double score = scoreFor(scores, candidateId);
            if (Double.isFinite(score) && score > 0.0d) {
                rankedIds.add(candidateId);
            }
        }
        rankedIds.sort(Comparator
                .comparingDouble((String id) -> scoreFor(scores, id))
                .reversed()
                .thenComparingInt(insertionOrder::get));
        Map<String, Integer> ranks = new HashMap<>();
        for (int index = 0; index < rankedIds.size(); index++) {
            ranks.put(rankedIds.get(index), index + 1);
        }
        return ranks;
    }

    private double contribution(Integer rank, double weight) {
        return rank == null ? 0.0d : weight / (RANK_CONSTANT + rank);
    }

    private double scoreFor(Map<String, Double> scores, String candidateId) {
        if (scores == null) {
            return 0.0d;
        }
        Double score = scores.get(candidateId);
        return score == null ? 0.0d : score;
    }
}
