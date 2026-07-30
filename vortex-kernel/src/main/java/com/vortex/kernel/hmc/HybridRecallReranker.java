package com.vortex.kernel.hmc;

import com.vortex.common.dto.RerankEffectStatus;
import com.vortex.common.model.MemoryFragment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Applies linear score fusion to semantic, lexical, and importance signals.
 * This component does not run a query-document cross-encoder model.
 */
@Slf4j
@Component
public class HybridRecallReranker {

    private static final WeightProfile DEFAULT_PROFILE = new WeightProfile(0.70, 0.25, 0.05);
    private static final WeightProfile TEMPORAL_REASONING_PROFILE = new WeightProfile(0.92, 0.03, 0.05);
    private static final WeightProfile MULTI_SESSION_REASONING_PROFILE = new WeightProfile(0.80, 0.10, 0.10);
    private static final WeightProfile KNOWLEDGE_UPDATE_PROFILE = new WeightProfile(0.75, 0.05, 0.20);
    private static final WeightProfile ABSTENTION_PROFILE = new WeightProfile(0.85, 0.05, 0.10);

    public List<HybridCandidate> rerank(
            Map<String, MemoryFragment> candidates,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            boolean keywordEnabled) {
        return rerank(candidates, semanticScores, keywordScores, keywordEnabled, List.of());
    }

    public List<HybridCandidate> rerank(
            Map<String, MemoryFragment> candidates,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            boolean keywordEnabled,
            List<String> requiredTags) {
        return rerankWithDiagnostics(
                candidates,
                semanticScores,
                keywordScores,
                keywordEnabled,
                requiredTags,
                candidates == null ? 0 : candidates.size())
                .candidates();
    }

    public RerankResult rerankWithDiagnostics(
            Map<String, MemoryFragment> candidates,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            boolean keywordEnabled,
            List<String> requiredTags,
            int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return new RerankResult(List.of(), RerankAnalysis.notExecuted());
        }
        WeightProfile profile = profileForTags(requiredTags);
        log.debug(
                "Hybrid recall rerank invoked candidateCount={} semanticScoreCount={} keywordScoreCount={} keywordEnabled={} profile={}",
                candidates.size(),
                semanticScores == null ? 0 : semanticScores.size(),
                keywordScores == null ? 0 : keywordScores.size(),
                keywordEnabled,
                profile);
        double semanticMax = maxScore(semanticScores);
        double keywordMax = maxScore(keywordScores);
        List<HybridCandidate> baseline = rankWithoutRerank(
                candidates,
                semanticScores,
                keywordScores,
                keywordEnabled);
        List<HybridCandidate> ranked = candidates.values().stream()
                .map(fragment -> {
                    double semanticScore = normalized(scoreFor(semanticScores, fragment.getId()), semanticMax);
                    double keywordScore = keywordEnabled
                            ? normalized(scoreFor(keywordScores, fragment.getId()), keywordMax)
                            : 0.0;
                    double importanceScore = clamp(fragment.getImportance());
                    double blendedScore = profile.semanticWeight() * semanticScore
                            + profile.keywordWeight() * keywordScore
                            + profile.importanceWeight() * importanceScore;
                    return new HybridCandidate(fragment, blendedScore, semanticScore, keywordScore);
                })
                .sorted(Comparator.comparingDouble(HybridCandidate::score).reversed())
                .toList();
        return new RerankResult(
                ranked,
                analyzeEffect(candidates, baseline, ranked, keywordEnabled, profile, topK));
    }

    public List<HybridCandidate> rankWithoutRerank(
            Map<String, MemoryFragment> candidates,
            Map<String, Double> semanticScores,
            Map<String, Double> keywordScores,
            boolean keywordEnabled) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.values().stream()
                .map(fragment -> {
                    double semanticScore = Math.max(0.0d, scoreFor(semanticScores, fragment.getId()));
                    double keywordScore = keywordEnabled
                            ? Math.max(0.0d, scoreFor(keywordScores, fragment.getId()))
                            : 0.0d;
                    return new HybridCandidate(
                            fragment,
                            semanticScore + keywordScore,
                            semanticScore,
                            keywordScore);
                })
                .sorted(Comparator.comparingDouble(HybridCandidate::score).reversed())
                .toList();
    }

    private RerankAnalysis analyzeEffect(
            Map<String, MemoryFragment> candidates,
            List<HybridCandidate> baseline,
            List<HybridCandidate> ranked,
            boolean keywordEnabled,
            WeightProfile profile,
            int topK) {
        int changedPositionCount = changedPositionCount(baseline, ranked);
        int topKMembershipChangedCount = topKMembershipChangedCount(baseline, ranked, topK);
        int semanticDistinctCount = distinctCount(ranked, HybridCandidate::semanticScore);
        int keywordDistinctCount = distinctCount(ranked, HybridCandidate::keywordScore);
        int importanceDistinctCount = distinctCount(
                candidates.values().stream().toList(),
                fragment -> clamp(fragment.getImportance()));
        boolean hasIndependentSignal = (keywordEnabled
                && profile.keywordWeight() > 0.0d
                && keywordDistinctCount > 1)
                || (profile.importanceWeight() > 0.0d && importanceDistinctCount > 1);
        RerankEffectStatus status;
        if (changedPositionCount > 0 || topKMembershipChangedCount > 0) {
            status = RerankEffectStatus.ORDER_CHANGED;
        } else if (hasIndependentSignal) {
            status = RerankEffectStatus.IDENTIFIABLE;
        } else {
            status = RerankEffectStatus.NON_IDENTIFIABLE;
        }
        return new RerankAnalysis(
                baseline.size(),
                ranked.size(),
                changedPositionCount,
                topKMembershipChangedCount,
                semanticDistinctCount,
                keywordDistinctCount,
                importanceDistinctCount,
                status);
    }

    private int changedPositionCount(List<HybridCandidate> baseline, List<HybridCandidate> ranked) {
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
            List<HybridCandidate> baseline,
            List<HybridCandidate> ranked,
            int topK) {
        int baselineLimit = Math.min(Math.max(0, topK), baseline.size());
        int rankedLimit = Math.min(Math.max(0, topK), ranked.size());
        Set<String> baselineIds = new LinkedHashSet<>();
        Set<String> rankedIds = new LinkedHashSet<>();
        baseline.stream().limit(baselineLimit).forEach(candidate -> baselineIds.add(candidate.fragment().getId()));
        ranked.stream().limit(rankedLimit).forEach(candidate -> rankedIds.add(candidate.fragment().getId()));
        return (int) baselineIds.stream().filter(id -> !rankedIds.contains(id)).count()
                + (int) rankedIds.stream().filter(id -> !baselineIds.contains(id)).count();
    }

    private <T> int distinctCount(List<T> values, ToDoubleFunction<T> extractor) {
        return (int) values.stream()
                .mapToLong(value -> Double.doubleToLongBits(canonicalZero(extractor.applyAsDouble(value))))
                .distinct()
                .count();
    }

    private WeightProfile profileForTags(List<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) {
            return DEFAULT_PROFILE;
        }
        List<String> normalizedTags = requiredTags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (containsTag(normalizedTags, "temporal-reasoning")) {
            return TEMPORAL_REASONING_PROFILE;
        }
        if (containsTag(normalizedTags, "multi-session-reasoning")) {
            return MULTI_SESSION_REASONING_PROFILE;
        }
        if (containsTag(normalizedTags, "knowledge-update") || containsTag(normalizedTags, "knowledge-updates")) {
            return KNOWLEDGE_UPDATE_PROFILE;
        }
        if (containsTag(normalizedTags, "abstention")) {
            return ABSTENTION_PROFILE;
        }
        return DEFAULT_PROFILE;
    }

    private boolean containsTag(List<String> tags, String value) {
        return tags.stream().anyMatch(tag -> tag.equals(value) || tag.endsWith("-" + value));
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

    private double scoreFor(Map<String, Double> scores, String fragmentId) {
        if (scores == null) {
            return 0.0d;
        }
        Double score = scores.get(fragmentId);
        return score == null ? 0.0d : score;
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

    private double canonicalZero(double value) {
        return value == 0.0d ? 0.0d : value;
    }

    public record HybridCandidate(
            MemoryFragment fragment,
            double score,
            double semanticScore,
            double keywordScore) {
    }

    public record RerankResult(
            List<HybridCandidate> candidates,
            RerankAnalysis analysis) {
    }

    public record RerankAnalysis(
            int inputCandidateCount,
            int outputCandidateCount,
            int changedPositionCount,
            int topKMembershipChangedCount,
            int semanticScoreDistinctCount,
            int keywordScoreDistinctCount,
            int importanceDistinctCount,
            RerankEffectStatus effectStatus) {

        static RerankAnalysis notExecuted() {
            return new RerankAnalysis(0, 0, 0, 0, 0, 0, 0, RerankEffectStatus.NOT_EXECUTED);
        }
    }

    record WeightProfile(
            double semanticWeight,
            double keywordWeight,
            double importanceWeight) {
    }
}
