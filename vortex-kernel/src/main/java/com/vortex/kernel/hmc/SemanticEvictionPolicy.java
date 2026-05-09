package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Semantic-LRU eviction policy.
 *
 * Score = α * recency + β * cosineSimilarity(fragment, currentTask) + γ * importance
 *
 * Fragments with the LOWEST score are evicted first.
 * α + β + γ should equal 1.0 (configurable).
 */
@Slf4j
@Component
public class SemanticEvictionPolicy {

    private final double alpha;
    private final double beta;
    private final double gamma;

    public SemanticEvictionPolicy(
            @Value("${vortex.kernel.eviction.alpha:0.3}") double alpha,
            @Value("${vortex.kernel.eviction.beta:0.5}") double beta,
            @Value("${vortex.kernel.eviction.gamma:0.2}") double gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    /**
     * Select fragments whose combined token count reaches the requested release target.
     *
     * @param candidates      all fragments currently in L1 for the namespace
     * @param queryEmbedding  embedding of the current task/query (may be null)
     * @param targetTokens    how many tokens should be freed
     */
    public List<MemoryFragment> selectForEviction(
            List<MemoryFragment> candidates,
            float[] queryEmbedding,
            long targetTokens) {
        return selectDetailedForEviction(candidates, queryEmbedding, targetTokens).stream()
                .map(EvictionCandidate::fragment)
                .toList();
    }

    public List<EvictionCandidate> selectDetailedForEviction(
            List<MemoryFragment> candidates,
            float[] queryEmbedding,
            long targetTokens) {
        List<EvictionCandidate> ranked = rankCandidates(candidates, queryEmbedding);
        return selectFromRanked(ranked, targetTokens);
    }

    public List<EvictionCandidate> selectDetailedForEviction(
            List<MemoryFragment> candidates,
            float[] queryEmbedding,
            long targetTokens,
            AdaptiveWeightProfile profile) {
        List<EvictionCandidate> ranked = rankCandidates(candidates, queryEmbedding, profile);
        return selectFromRanked(ranked, targetTokens);
    }

    private List<EvictionCandidate> selectFromRanked(List<EvictionCandidate> ranked, long targetTokens) {
        if (targetTokens <= 0 || ranked.isEmpty()) {
            return List.of();
        }

        List<EvictionCandidate> selected = new ArrayList<>();
        long releasedTokens = 0;
        List<String> accountedGroups = new ArrayList<>();
        for (EvictionCandidate fragment : ranked) {
            if (releasedTokens >= targetTokens) {
                break;
            }
            String groupKey = candidateGroupKey(fragment);
            if (accountedGroups.contains(groupKey)) {
                continue;
            }
            accountedGroups.add(groupKey);
            selected.add(fragment);
            releasedTokens += fragment.groupTokenCount();
        }
        return selected;
    }

    public List<EvictionCandidate> rankCandidates(Collection<MemoryFragment> candidates, float[] queryEmbedding) {
        return rankCandidates(candidates, queryEmbedding, defaultProfile());
    }

    public List<EvictionCandidate> rankCandidates(
            Collection<MemoryFragment> candidates,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, List<MemoryFragment>> reasoningGroups = buildReasoningGroups(candidates);
        Map<String, MemoryFragment.EvictionScoreBreakdown> breakdowns = candidates.stream()
                .collect(Collectors.toMap(
                        MemoryFragment::getId,
                        fragment -> computeBreakdown(fragment, queryEmbedding, candidates, profile)));
        Map<String, Long> groupTokenCounts = reasoningGroups.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream().mapToLong(MemoryFragment::getTokenCount).sum()));
        Map<String, Double> groupScores = reasoningGroups.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(MemoryFragment::getId)
                                .map(breakdowns::get)
                                .mapToDouble(MemoryFragment.EvictionScoreBreakdown::totalScore)
                                .average()
                                .orElse(0.0)));

        return candidates.stream()
                .map(fragment -> buildCandidate(
                        fragment,
                        breakdowns.get(fragment.getId()),
                        groupTokenCounts.getOrDefault(groupKey(fragment), (long) fragment.getTokenCount()),
                        groupScores.getOrDefault(groupKey(fragment), breakdowns.get(fragment.getId()).totalScore())))
                .filter(candidate -> !candidate.pinned())
                .sorted(Comparator.comparingDouble(EvictionCandidate::density)
                        .thenComparingDouble(EvictionCandidate::totalScore))
                .toList();
    }

    public EvictionCandidate scoreFragment(MemoryFragment fragment, float[] queryEmbedding) {
        return scoreFragment(fragment, queryEmbedding, defaultProfile());
    }

    public EvictionCandidate scoreFragment(
            MemoryFragment fragment,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile) {
        return scoreFragment(fragment, queryEmbedding, List.of(fragment), Map.of(), profile);
    }

    private EvictionCandidate scoreFragment(
            MemoryFragment fragment,
            float[] queryEmbedding,
            Collection<MemoryFragment> candidatePool,
            Map<String, List<MemoryFragment>> reasoningGroups,
            AdaptiveWeightProfile profile) {
        MemoryFragment.EvictionScoreBreakdown breakdown = computeBreakdown(fragment, queryEmbedding, candidatePool, profile);
        List<MemoryFragment> reasoningGroup = reasoningGroups.getOrDefault(groupKey(fragment), List.of(fragment));
        long groupTokenCount = reasoningGroup.stream().mapToLong(MemoryFragment::getTokenCount).sum();
        double groupScore = reasoningGroup.stream()
                .mapToDouble(groupFragment -> computeBreakdown(groupFragment, queryEmbedding, candidatePool, profile).totalScore())
                .average()
                .orElse(breakdown.totalScore());
        return buildCandidate(fragment, breakdown, groupTokenCount, groupScore);
    }

    private MemoryFragment.EvictionScoreBreakdown computeBreakdown(
            MemoryFragment fragment,
            float[] queryEmbedding,
            Collection<MemoryFragment> candidatePool,
            AdaptiveWeightProfile profile) {
        double redundancyPenalty = candidatePool.stream()
                .filter(other -> other != fragment)
                .mapToDouble(fragment::redundancyPenaltyAgainst)
                .max()
                .orElse(0.0);
        double noveltyBonus = candidatePool.stream()
                .filter(other -> other != fragment)
                .mapToDouble(fragment::noveltyBonusAgainst)
                .min()
                .orElse(0.0);
        return fragment.describeEvictionScore(
                queryEmbedding,
                profile.getAlpha(),
                profile.getBeta(),
                profile.getGamma(),
                redundancyPenalty,
                noveltyBonus);
    }

    private EvictionCandidate buildCandidate(
            MemoryFragment fragment,
            MemoryFragment.EvictionScoreBreakdown breakdown,
            long groupTokenCount,
            double groupScore) {
        double density = groupScore / Math.max(1L, groupTokenCount);
        return new EvictionCandidate(
                fragment,
                breakdown.recencyScore(),
                breakdown.similarityScore(),
                breakdown.importanceScore(),
                breakdown.recencyContribution(),
                breakdown.similarityContribution(),
                breakdown.importanceContribution(),
                breakdown.redundancyPenalty(),
                breakdown.noveltyBonus(),
                breakdown.totalScore(),
                density,
                fragment.isPinned(),
                fragment.getReasoningChainId(),
                groupTokenCount,
                groupScore
        );
    }

    private Map<String, List<MemoryFragment>> buildReasoningGroups(Collection<MemoryFragment> candidates) {
        Map<String, List<MemoryFragment>> grouped = candidates.stream()
                .collect(Collectors.groupingBy(fragment -> groupKey(fragment)));
        return grouped.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String groupKey(MemoryFragment fragment) {
        if (fragment.getReasoningChainId() == null || fragment.getReasoningChainId().isBlank()) {
            return "__self__:" + fragment.getId();
        }
        return fragment.getReasoningChainId();
    }

    private String candidateGroupKey(EvictionCandidate candidate) {
        if (candidate.reasoningChainId() == null || candidate.reasoningChainId().isBlank()) {
            return "__self__:" + candidate.fragment().getId();
        }
        return candidate.reasoningChainId();
    }

    public AdaptiveWeightProfile defaultProfile() {
        return AdaptiveWeightProfile.builder()
                .profileName("static-default")
                .alpha(alpha)
                .beta(beta)
                .gamma(gamma)
                .build();
    }

    public record EvictionCandidate(
            MemoryFragment fragment,
            double recencyScore,
            double similarityScore,
            double importanceScore,
            double recencyContribution,
            double similarityContribution,
            double importanceContribution,
            double redundancyPenalty,
            double noveltyBonus,
            double totalScore,
            double density,
            boolean pinned,
            String reasoningChainId,
            long groupTokenCount,
            double groupScore) {
    }
}
