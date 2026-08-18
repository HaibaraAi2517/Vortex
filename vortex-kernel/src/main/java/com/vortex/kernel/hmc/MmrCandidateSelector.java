package com.vortex.kernel.hmc;

import java.util.ArrayList;
import java.util.List;

/** Dynamically selects diverse recall candidates with maximal marginal relevance. */
final class MmrCandidateSelector {

    private static final double RELEVANCE_WEIGHT = 0.85d;
    private static final double DIVERSITY_PENALTY_WEIGHT = 0.15d;
    private static final double NEAR_DUPLICATE_THRESHOLD = 0.95d;
    private static final int CANDIDATE_POOL_MULTIPLIER = 2;

    List<HybridRecallReranker.HybridCandidate> select(
            List<HybridRecallReranker.HybridCandidate> rankedCandidates) {
        return select(rankedCandidates, rankedCandidates == null ? 0 : rankedCandidates.size());
    }

    List<HybridRecallReranker.HybridCandidate> select(
            List<HybridRecallReranker.HybridCandidate> rankedCandidates,
            int topK) {
        if (rankedCandidates == null || rankedCandidates.size() < 2) {
            return rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
        }
        int resolvedTopK = Math.max(1, topK);
        int poolSize = Math.min(
                rankedCandidates.size(),
                Math.max(resolvedTopK, resolvedTopK * CANDIDATE_POOL_MULTIPLIER));
        List<HybridRecallReranker.HybridCandidate> remaining =
                new ArrayList<>(rankedCandidates.subList(0, poolSize));
        List<HybridRecallReranker.HybridCandidate> selected = new ArrayList<>(poolSize);
        while (!remaining.isEmpty()) {
            int bestIndex = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < remaining.size(); index++) {
                HybridRecallReranker.HybridCandidate candidate = remaining.get(index);
                double mmrScore = RELEVANCE_WEIGHT * clamp(candidate.score())
                        - DIVERSITY_PENALTY_WEIGHT * maximumSimilarity(candidate, selected);
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestIndex = index;
                }
            }
            selected.add(remaining.remove(bestIndex));
        }
        if (poolSize < rankedCandidates.size()) {
            selected.addAll(rankedCandidates.subList(poolSize, rankedCandidates.size()));
        }
        return List.copyOf(selected);
    }

    private double maximumSimilarity(
            HybridRecallReranker.HybridCandidate candidate,
            List<HybridRecallReranker.HybridCandidate> selected) {
        double maximum = 0.0d;
        for (HybridRecallReranker.HybridCandidate selectedCandidate : selected) {
            maximum = Math.max(
                    maximum,
                    candidate.fragment().redundancySimilarityTo(selectedCandidate.fragment()));
        }
        if (maximum <= NEAR_DUPLICATE_THRESHOLD) {
            return 0.0d;
        }
        return clamp((maximum - NEAR_DUPLICATE_THRESHOLD) / (1.0d - NEAR_DUPLICATE_THRESHOLD));
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
