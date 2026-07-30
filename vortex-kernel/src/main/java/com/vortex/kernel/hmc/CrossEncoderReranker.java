package com.vortex.kernel.hmc;

import com.vortex.common.dto.RerankEffectStatus;
import com.vortex.common.model.MemoryFragment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies independent cross-encoder scores to a bounded baseline-ranked candidate pool. */
public final class CrossEncoderReranker {

    public static final int DEFAULT_CANDIDATE_POOL_LIMIT = 40;
    private static final String SHA256_PATTERN = "(?i)[0-9a-f]{64}";

    private final CrossEncoderScoringService scoringService;
    private final HybridRecallReranker baselineRanker;

    public CrossEncoderReranker(CrossEncoderScoringService scoringService) {
        this(scoringService, new HybridRecallReranker());
    }

    CrossEncoderReranker(
            CrossEncoderScoringService scoringService,
            HybridRecallReranker baselineRanker) {
        this.scoringService = scoringService;
        this.baselineRanker = baselineRanker;
    }

    public Result rerank(
            String query,
            Map<String, MemoryFragment> candidates,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            boolean keywordEnabled,
            int candidatePoolLimit,
            int topK) {
        if (scoringService == null) {
            throw new IllegalStateException(
                    "CROSS_ENCODER was requested but no CrossEncoderScoringService is configured");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Cross-encoder reranking requires a non-blank query");
        }
        if (candidatePoolLimit <= 0) {
            throw new IllegalArgumentException("Cross-encoder candidate pool limit must be greater than zero");
        }
        if (topK <= 0 || topK > candidatePoolLimit) {
            throw new IllegalArgumentException(
                    "Cross-encoder topK must be between 1 and the candidate pool limit");
        }

        CrossEncoderScoringService.ModelMetadata metadata = validateMetadata(scoringService.metadata());
        List<HybridRecallReranker.HybridCandidate> baseline = baselineRanker.rankWithoutRerank(
                candidates,
                semanticScores,
                keywordScores,
                keywordEnabled);
        List<HybridRecallReranker.HybridCandidate> candidatePool = baseline.stream()
                .limit(candidatePoolLimit)
                .toList();
        String strategy = keywordEnabled
                ? "HYBRID_BASELINE_TOP_" + candidatePoolLimit
                : "VECTOR_BASELINE_TOP_" + candidatePoolLimit;
        if (candidatePool.isEmpty()) {
            return new Result(
                    List.of(),
                    new Analysis(
                            baseline.size(),
                            0,
                            0,
                            candidatePoolLimit,
                            strategy,
                            0,
                            0,
                            0,
                            0L,
                            metadata,
                            RerankEffectStatus.NOT_EXECUTED));
        }

        List<String> documents = candidatePool.stream()
                .map(candidate -> {
                    String content = candidate.fragment().getContent();
                    return content == null ? "" : content;
                })
                .toList();
        long startedAt = System.nanoTime();
        List<Double> scores = scoringService.score(query, documents);
        long latencyNanos = System.nanoTime() - startedAt;
        validateScores(scores, candidatePool.size());

        List<ScoredCandidate> scored = new ArrayList<>(candidatePool.size());
        for (int index = 0; index < candidatePool.size(); index++) {
            HybridRecallReranker.HybridCandidate baselineCandidate = candidatePool.get(index);
            scored.add(new ScoredCandidate(baselineCandidate, scores.get(index), index));
        }
        List<HybridRecallReranker.HybridCandidate> ranked = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::score)
                        .reversed()
                        .thenComparingInt(ScoredCandidate::baselineIndex))
                .map(candidate -> new HybridRecallReranker.HybridCandidate(
                        candidate.baselineCandidate().fragment(),
                        candidate.score(),
                        candidate.baselineCandidate().semanticScore(),
                        candidate.baselineCandidate().keywordScore()))
                .toList();

        int changedPositionCount = changedPositionCount(candidatePool, ranked);
        int topKMembershipChangedCount = topKMembershipChangedCount(candidatePool, ranked, topK);
        int scoreDistinctCount = scoreDistinctCount(scores);
        RerankEffectStatus effectStatus;
        if (changedPositionCount > 0 || topKMembershipChangedCount > 0) {
            effectStatus = RerankEffectStatus.ORDER_CHANGED;
        } else if (scoreDistinctCount > 1) {
            effectStatus = RerankEffectStatus.IDENTIFIABLE;
        } else {
            effectStatus = RerankEffectStatus.NON_IDENTIFIABLE;
        }
        return new Result(
                ranked,
                new Analysis(
                        baseline.size(),
                        candidatePool.size(),
                        ranked.size(),
                        candidatePoolLimit,
                        strategy,
                        changedPositionCount,
                        topKMembershipChangedCount,
                        scoreDistinctCount,
                        latencyNanos,
                        metadata,
                        effectStatus));
    }

    private CrossEncoderScoringService.ModelMetadata validateMetadata(
            CrossEncoderScoringService.ModelMetadata metadata) {
        if (metadata == null
                || isBlank(metadata.model())
                || isBlank(metadata.version())
                || isBlank(metadata.sha256())) {
            throw new IllegalStateException(
                    "Cross-encoder model, version, and SHA-256 metadata are required");
        }
        if (!metadata.sha256().matches(SHA256_PATTERN)) {
            throw new IllegalStateException("Cross-encoder model SHA-256 must contain 64 hex characters");
        }
        return metadata;
    }

    private void validateScores(List<Double> scores, int expectedCount) {
        if (scores == null || scores.size() != expectedCount) {
            throw new IllegalStateException(
                    "Cross-encoder returned %d scores for %d documents"
                            .formatted(scores == null ? 0 : scores.size(), expectedCount));
        }
        for (int index = 0; index < scores.size(); index++) {
            Double score = scores.get(index);
            if (score == null || !Double.isFinite(score)) {
                throw new IllegalStateException(
                        "Cross-encoder returned a non-finite score at index " + index);
            }
        }
    }

    private int changedPositionCount(
            List<HybridRecallReranker.HybridCandidate> baseline,
            List<HybridRecallReranker.HybridCandidate> ranked) {
        Map<String, Integer> baselinePositions = new HashMap<>();
        for (int index = 0; index < baseline.size(); index++) {
            baselinePositions.put(baseline.get(index).fragment().getId(), index);
        }
        int changed = 0;
        for (int index = 0; index < ranked.size(); index++) {
            Integer baselinePosition = baselinePositions.get(ranked.get(index).fragment().getId());
            if (baselinePosition == null || baselinePosition != index) {
                changed++;
            }
        }
        return changed;
    }

    private int topKMembershipChangedCount(
            List<HybridRecallReranker.HybridCandidate> baseline,
            List<HybridRecallReranker.HybridCandidate> ranked,
            int topK) {
        int limit = Math.min(topK, Math.min(baseline.size(), ranked.size()));
        Set<String> baselineIds = new HashSet<>();
        Set<String> rankedIds = new HashSet<>();
        baseline.stream().limit(limit).forEach(candidate -> baselineIds.add(candidate.fragment().getId()));
        ranked.stream().limit(limit).forEach(candidate -> rankedIds.add(candidate.fragment().getId()));
        return (int) baselineIds.stream().filter(id -> !rankedIds.contains(id)).count()
                + (int) rankedIds.stream().filter(id -> !baselineIds.contains(id)).count();
    }

    private int scoreDistinctCount(List<Double> scores) {
        return (int) scores.stream()
                .mapToLong(score -> Double.doubleToLongBits(score == 0.0d ? 0.0d : score))
                .distinct()
                .count();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ScoredCandidate(
            HybridRecallReranker.HybridCandidate baselineCandidate,
            double score,
            int baselineIndex) {
    }

    public record Result(
            List<HybridRecallReranker.HybridCandidate> candidates,
            Analysis analysis) {
    }

    public record Analysis(
            int preselectionCandidateCount,
            int inputCandidateCount,
            int outputCandidateCount,
            int candidatePoolLimit,
            String candidatePoolStrategy,
            int changedPositionCount,
            int topKMembershipChangedCount,
            int scoreDistinctCount,
            long latencyNanos,
            CrossEncoderScoringService.ModelMetadata modelMetadata,
            RerankEffectStatus effectStatus) {
    }
}
