package com.vortex.kernel.hmc;

import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallDiagnostics;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.exception.EmbeddingException;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.paging.SemanticPagingManager;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Semantic recall orchestrator.
 *
 * Handles the full recall pipeline:
 *   L1 embedding (BGE-Small) scoring of L1 candidates,
 *   L2 search (Milvus) for L1 misses,
 *   multi-arm ranking with adaptive weight profiles,
 *   shadow evaluation and regret tracking.
 */
@Slf4j
@Component
public class RecallOrchestrator {

    private final L1HotStore l1;
    private final L2WarmStore l2;
    private final L3ColdStore l3;
    private final EmbeddingService l1EmbeddingService;
    private final EmbeddingService l2EmbeddingService;
    private final AdaptiveWeightLearner adaptiveWeightLearner;
    private final SemanticEvictionPolicy evictionPolicy;
    private final EvictionRegretTracker regretTracker;
    private final MemorySloTracker sloTracker;
    private final FragmentPersistenceManager persistenceManager;
    private final SemanticPagingManager pagingManager;
    private final RedundancyAnalyzer redundancyAnalyzer;
    private final FragmentPinManager pinManager;
    private final TieredEvictionCoordinator evictionCoordinator;

    @Autowired
    public RecallOrchestrator(
            L1HotStore l1,
            L2WarmStore l2,
            L3ColdStore l3,
            @Qualifier("bgeSmallEmbeddingService") EmbeddingService l1EmbeddingService,
            @Qualifier("cloudEmbeddingService") ObjectProvider<EmbeddingService> cloudEmbeddingProvider,
            AdaptiveWeightLearner adaptiveWeightLearner,
            SemanticEvictionPolicy evictionPolicy,
            EvictionRegretTracker regretTracker,
            MemorySloTracker sloTracker,
            FragmentPersistenceManager persistenceManager,
            ObjectProvider<SemanticPagingManager> pagingManagerProvider,
            RedundancyAnalyzer redundancyAnalyzer,
            FragmentPinManager pinManager,
            TieredEvictionCoordinator evictionCoordinator) {
        this.l1 = l1;
        this.l2 = l2;
        this.l3 = l3;
        this.l1EmbeddingService = l1EmbeddingService;
        this.l2EmbeddingService = cloudEmbeddingProvider.getIfAvailable();
        this.adaptiveWeightLearner = adaptiveWeightLearner;
        this.evictionPolicy = evictionPolicy;
        this.regretTracker = regretTracker;
        this.sloTracker = sloTracker;
        this.persistenceManager = persistenceManager;
        this.pagingManager = pagingManagerProvider.getIfAvailable();
        this.redundancyAnalyzer = redundancyAnalyzer;
        this.pinManager = pinManager;
        this.evictionCoordinator = evictionCoordinator;
    }

    /**
     * Semantic recall across L1 and L2.
     *
     * 1. Embed query with BGE-Small, score all L1 fragments by cosine similarity.
     * 2. If cloud embedding is enabled, also embed query with DeepSeek, search L2 Milvus.
     * 3. Prefetch L2 results back into L1 for subsequent calls.
     */
    public RecallResult recall(RecallQuery query) {
        long startedAt = System.nanoTime();
        List<String> requiredTags = normalizeTags(query.getTags());
        RecallDiagnosticsAccumulator diagnostics = new RecallDiagnosticsAccumulator(requiredTags);
        MemoryScenario scenario = query.getScenario() == null ? MemoryScenario.CHAT : query.getScenario();
        AdaptiveWeightLearner.ProfileSelection profileSelection = adaptiveWeightLearner.selectProfiles(scenario);
        AdaptiveWeightProfile baselineProfile = evictionPolicy.defaultProfile();
        // L1 query embedding — BGE-Small (fast, local)
        float[] l1QueryEmbedding = requireEmbedding(l1EmbeddingService, query.getQuery(), "L1 recall query");
        // L2 query embedding — DeepSeek if available, else reuse BGE-Small
        float[] l2QueryEmbedding = resolveL2QueryEmbedding(query.getQuery(), l1QueryEmbedding);

        List<RecallResult.ScoredFragment> results = new ArrayList<>();
        int tokensSoFar = 0;

        List<MemoryFragment> l1Candidates = l1.getAll(query.getNamespace());
        diagnostics.setL1CandidateCount(l1Candidates.size());
        List<MemoryFragment> filteredL1Candidates = l1Candidates.stream()
                .filter(fragment -> matchesAllTags(fragment, requiredTags))
                .toList();
        diagnostics.setL1TagMatchedCount(filteredL1Candidates.size());
        List<RankedRecallCandidate> activeSelected = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        for (ScoredCandidate candidate : rankForRecall(filteredL1Candidates, l1QueryEmbedding, profileSelection.active())) {
            if (activeSelected.size() >= query.getTopK()) {
                break;
            }
            if (tokensSoFar + candidate.fragment().getTokenCount() > query.getTokenBudget()) {
                diagnostics.incrementL1TokenBudgetRejectedCount();
                continue;
            }
            activeSelected.add(new RankedRecallCandidate(candidate.fragment(), candidate.score(), "L1"));
            selectedIds.add(candidate.fragment().getId());
            tokensSoFar += candidate.fragment().getTokenCount();
            diagnostics.incrementL1SelectedCount();
        }

        List<MemoryFragment> evaluationPool = new ArrayList<>(filteredL1Candidates);
        if (activeSelected.size() < query.getTopK()) {
            int needed = query.getTopK() - activeSelected.size();
            int l2SearchLimit = Math.max(
                    needed * 4,
                    query.getTopK() * 4);
            RedundancyAnalyzer.IncrementalRedundancyState redundancyState = RedundancyAnalyzer.IncrementalRedundancyState.from(filteredL1Candidates);
            Set<String> attemptedL2Ids = new HashSet<>();
            tokensSoFar = appendL2Candidates(
                    l2.search(l2QueryEmbedding, query.getNamespace(), l2SearchLimit),
                    RecallCandidateSource.L2_SEARCH,
                    query,
                    requiredTags,
                    l1QueryEmbedding,
                    profileSelection.active(),
                    activeSelected,
                    selectedIds,
                    evaluationPool,
                    redundancyState,
                    attemptedL2Ids,
                    tokensSoFar,
                    diagnostics);
            if (activeSelected.size() < query.getTopK()) {
                int fallbackLimit = Math.max(l2SearchLimit, query.getTopK() * 8);
                tokensSoFar = appendL2Candidates(
                        l2.listByNamespace(query.getNamespace(), fallbackLimit),
                        RecallCandidateSource.L2_NAMESPACE_FALLBACK,
                        query,
                        requiredTags,
                        l1QueryEmbedding,
                        profileSelection.active(),
                        activeSelected,
                        selectedIds,
                        evaluationPool,
                        redundancyState,
                        attemptedL2Ids,
                        tokensSoFar,
                        diagnostics);
            }
        }

        List<ScoredCandidate> activeRanked = rankForRecall(evaluationPool, l1QueryEmbedding, profileSelection.active());
        List<ScoredCandidate> shadowRanked = rankForRecall(evaluationPool, l1QueryEmbedding, profileSelection.shadow());
        List<ScoredCandidate> baselineRanked = rankForRecall(evaluationPool, l1QueryEmbedding, baselineProfile);
        List<String> activeEvictionRanked = evictionCoordinator.rankEvictionForEvaluation(evaluationPool, l1QueryEmbedding, profileSelection.active());
        List<String> shadowEvictionRanked = evictionCoordinator.rankEvictionForEvaluation(evaluationPool, l1QueryEmbedding, profileSelection.shadow());
        List<String> baselineEvictionRanked = evictionCoordinator.rankEvictionForEvaluation(evaluationPool, l1QueryEmbedding, baselineProfile);

        for (RankedRecallCandidate selected : activeSelected) {
            MemoryFragment recalled;
            if ("L1".equals(selected.tier())) {
                recalled = refreshL1Fragment(selected.fragment());
            } else {
                recalled = prepareL2RecallCandidate(selected.fragment());
            }
            results.add(RecallResult.ScoredFragment.builder()
                    .fragment(recalled)
                    .score(selected.score())
                    .tier(selected.tier())
                    .build());
        }

        List<String> trace = results.stream().map(RecallResult.ScoredFragment::getTier).toList();
        diagnostics.setFinalReturnedCount(results.size());
        diagnostics.resolveEmptyRecallReason();
        String recallSessionId = adaptiveWeightLearner.recordRecallSession(RecallSessionRecord.builder()
                .namespace(query.getNamespace())
                .scenario(scenario)
                .activeProfileName(profileSelection.active().getProfileName())
                .shadowProfileName(profileSelection.shadow().getProfileName())
                .activeArmIndex(extractArmIndex(profileSelection.active().getProfileName()))
                .shadowArmIndex(extractArmIndex(profileSelection.shadow().getProfileName()))
                .activeSelectionProbability(extractSelectionProbability(profileSelection.active().getProfileName()))
                .shadowSelectionProbability(extractSelectionProbability(profileSelection.shadow().getProfileName()))
                .rankedFragmentIds(activeRanked.stream().map(sc -> sc.fragment().getId()).toList())
                .shadowRankedFragmentIds(shadowRanked.stream().map(sc -> sc.fragment().getId()).toList())
                .baselineRankedFragmentIds(baselineRanked.stream().map(sc -> sc.fragment().getId()).toList())
                .returnedFragmentIds(activeSelected.stream().map(selected -> selected.fragment().getId()).toList())
                .activeEvictionRankedFragmentIds(activeEvictionRanked)
                .shadowEvictionRankedFragmentIds(shadowEvictionRanked)
                .baselineEvictionRankedFragmentIds(baselineEvictionRanked)
                .createdAt(java.time.Instant.now())
                .build());
        EvictionRegretTracker.RegretSnapshot regretSnapshot = regretTracker.snapshot();
        sloTracker.recordRegretRate(regretSnapshot.regretRate());
        ShadowEvaluationTracker.ShadowEvaluationSnapshot learningSnapshot =
                adaptiveWeightLearner.snapshot(scenario).shadowEvaluation();
        sloTracker.recordLearningLift(
                learningSnapshot.relativeLift(),
                learningSnapshot.baselineRelativeLift(),
                learningSnapshot.baselineWinRate());
        sloTracker.recordRecallLatency(System.nanoTime() - startedAt);

        // Trigger semantic neighborhood prefetch (async, best-effort)
        if (pagingManager != null) {
            pagingManager.onRecall(l1QueryEmbedding);
        }

        return RecallResult.builder()
                .fragments(results)
                .totalTokens(tokensSoFar)
                .sourceTrace(trace)
                .recallSessionId(recallSessionId)
                .activeProfileName(profileSelection.active().getProfileName())
                .shadowProfileName(profileSelection.shadow().getProfileName())
                .diagnostics(diagnostics.toDiagnostics())
                .build();
    }

    // ---- Ranking helpers ----

    private MemoryFragment refreshL1Fragment(MemoryFragment fragment) {
        MemoryFragment refreshed = l1.get(fragment.getId()).orElse(fragment);
        refreshed.clearExpiredPin();
        refreshed.reinforceImportanceOnRecall();
        evictionCoordinator.reindexTierMembership(refreshed);
        if (pagingManager != null) {
            pagingManager.onFragmentAccess(refreshed.getId());
        }
        return refreshed;
    }

    private MemoryFragment prepareL2RecallCandidate(MemoryFragment candidate) {
        candidate.clearExpiredPin();
        evictionCoordinator.admitToL1(candidate, "recall-reinforcement");
        persistenceManager.persistAsync(candidate, "recall-reinforcement");
        regretTracker.recordRecall(candidate, "L2");
        evictionCoordinator.reindexTierMembership(candidate);
        if (pagingManager != null) {
            pagingManager.onFragmentAccess(candidate.getId());
        }
        return candidate;
    }

    // ---- Internal recall helpers ----

    private List<ScoredCandidate> rankForRecall(
            List<MemoryFragment> candidates,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile) {
        Map<String, RedundancyAnalyzer.RedundancyStats> redundancyStats = redundancyAnalyzer.computeRedundancyStats(candidates);
        return candidates.stream()
                .map(fragment -> new ScoredCandidate(
                        fragment,
                        scoreForRecall(fragment, queryEmbedding, profile, redundancyStats)))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
    }

    private int appendL2Candidates(
            List<MemoryFragment> l2Hits,
            RecallCandidateSource candidateSource,
            RecallQuery query,
            List<String> requiredTags,
            float[] l1QueryEmbedding,
            AdaptiveWeightProfile profile,
            List<RankedRecallCandidate> activeSelected,
            Set<String> selectedIds,
            List<MemoryFragment> evaluationPool,
            RedundancyAnalyzer.IncrementalRedundancyState redundancyState,
            Set<String> attemptedL2Ids,
            int tokensSoFar,
            RecallDiagnosticsAccumulator diagnostics) {
        diagnostics.recordCandidateCount(candidateSource, l2Hits == null ? 0 : l2Hits.size());
        for (MemoryFragment hit : l2Hits == null ? List.<MemoryFragment>of() : l2Hits) {
            if (activeSelected.size() >= query.getTopK()) {
                break;
            }
            if (hit == null) {
                continue;
            }
            if (!attemptedL2Ids.add(hit.getId()) || selectedIds.contains(hit.getId())) {
                diagnostics.incrementDuplicateRejectedCount(candidateSource);
                continue;
            }
            MemoryFragment candidate = enrichForRecall(hit, requiredTags, diagnostics);
            if (candidate == null) {
                diagnostics.incrementTagRejectedCount(candidateSource);
                continue;
            }
            if (candidate.getEmbedding() == null) {
                ensureL1Embedding(candidate);
            }
            candidate.reinforceImportanceOnRecall();
            if (tokensSoFar + candidate.getTokenCount() > query.getTokenBudget()) {
                diagnostics.incrementTokenBudgetRejectedCount(candidateSource);
                continue;
            }
            redundancyState.add(candidate);
            activeSelected.add(new RankedRecallCandidate(
                    candidate,
                    scoreForRecall(candidate, l1QueryEmbedding, profile, redundancyState.snapshot()),
                    "L2"));
            diagnostics.incrementAcceptedCount(candidateSource);
            selectedIds.add(candidate.getId());
            tokensSoFar += candidate.getTokenCount();
            evaluationPool.add(candidate);

            // Trigger page fault for this L2 fragment (async, best-effort)
            if (pagingManager != null) {
                pagingManager.handlePageFault(candidate.getId(), query.getNamespace());
            }
        }
        return tokensSoFar;
    }

    private double scoreForRecall(
            MemoryFragment fragment,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile,
            Map<String, RedundancyAnalyzer.RedundancyStats> redundancyStats) {
        RedundancyAnalyzer.RedundancyStats stats = redundancyStats.getOrDefault(fragment.getId(), new RedundancyAnalyzer.RedundancyStats(0.0, 0.0));
        return fragment.describeEvictionScore(
                queryEmbedding,
                profile.getAlpha(),
                profile.getBeta(),
                profile.getGamma(),
                stats.redundancyPenalty(),
                stats.noveltyBonus()).totalScore();
    }

    private List<MemoryFragment> appendCandidate(List<MemoryFragment> candidates, MemoryFragment candidate) {
        List<MemoryFragment> expanded = new ArrayList<>(candidates);
        expanded.add(candidate);
        return expanded;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    private MemoryFragment enrichForRecall(
            MemoryFragment candidate,
            List<String> requiredTags,
            RecallDiagnosticsAccumulator diagnostics) {
        MemoryFragment fragment = findFragment(candidate.getId(), diagnostics).orElse(candidate);
        if (matchesAllTags(fragment, requiredTags)) {
            diagnostics.incrementEnrichFragmentTagMatchedCount();
            return fragment;
        }
        if (matchesAllTags(candidate, requiredTags)) {
            diagnostics.incrementEnrichCandidateTagMatchedCount();
            return candidate;
        }
        Optional<MemoryFragment> l2Fragment = l2.get(candidate.getId());
        if (l2Fragment.isPresent() && matchesAllTags(l2Fragment.get(), requiredTags)) {
            diagnostics.incrementEnrichL2TagFallbackMatchedCount();
            return l2Fragment.get();
        }
        diagnostics.incrementEnrichTagRejectedCount();
        return null;
    }

    private Optional<MemoryFragment> findFragment(String fragmentId, RecallDiagnosticsAccumulator diagnostics) {
        Optional<MemoryFragment> l1Fragment = l1.peek(fragmentId);
        if (l1Fragment.isPresent()) {
            diagnostics.incrementFindFragmentL1HitCount();
            return l1Fragment;
        }
        Optional<MemoryFragment> archivedFragment = l3.retrieveFragment(fragmentId);
        if (archivedFragment.isPresent()) {
            diagnostics.incrementFindFragmentL3HitCount();
            return archivedFragment;
        }
        Optional<MemoryFragment> l2Fragment = l2.get(fragmentId);
        if (l2Fragment.isPresent()) {
            diagnostics.incrementFindFragmentL2HitCount();
        } else {
            diagnostics.incrementFindFragmentMissCount();
        }
        return l2Fragment;
    }

    // ---- Embedding helpers ----

    private void ensureL1Embedding(MemoryFragment fragment) {
        if (fragment.getEmbedding() == null) {
            fragment.setEmbedding(requireEmbedding(l1EmbeddingService, fragment.getContent(),
                    "L1 fragment " + fragment.getId()));
        }
    }

    private float[] requireEmbedding(EmbeddingService embeddingService, String text, String context) {
        try {
            return embeddingService.embed(text);
        } catch (EmbeddingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EmbeddingException(context + " failed: " + e.getMessage(), e);
        }
    }

    private float[] resolveL2QueryEmbedding(String query, float[] l1QueryEmbedding) {
        if (l2EmbeddingService == null) {
            return l1QueryEmbedding;
        }
        try {
            return requireEmbedding(l2EmbeddingService, query, "L2 recall query");
        } catch (EmbeddingException e) {
            log.warn("L2 query embedding failed, falling back to L1 query embedding: {}", e.getMessage());
            return l1QueryEmbedding;
        }
    }

    // ---- Profile parsing helpers ----

    private Integer extractArmIndex(String profileName) {
        return parseProfileSuffix(profileName, "arm");
    }

    private double extractSelectionProbability(String profileName) {
        Integer probabilityEncoded = parseProfileSuffix(profileName, "p");
        return probabilityEncoded == null ? 0.0 : probabilityEncoded / 10_000.0;
    }

    private Integer parseProfileSuffix(String profileName, String marker) {
        if (profileName == null) {
            return null;
        }
        String token = "-" + marker;
        int start = profileName.indexOf(token);
        if (start < 0) {
            return null;
        }
        int valueStart = start + token.length();
        int valueEnd = valueStart;
        while (valueEnd < profileName.length() && Character.isDigit(profileName.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            return null;
        }
        return Integer.parseInt(profileName.substring(valueStart, valueEnd));
    }

    // ---- Tag helpers ----

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
    }

    private boolean matchesAllTags(MemoryFragment fragment, List<String> requiredTags) {
        if (requiredTags.isEmpty()) {
            return true;
        }
        List<String> fragmentTags = fragment.getTags();
        if (fragmentTags == null || fragmentTags.isEmpty()) {
            return false;
        }
        Set<String> tagSet = fragmentTags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .collect(Collectors.toSet());
        return requiredTags.stream().allMatch(tagSet::contains);
    }

    // ---- Inner types ----

    record ScoredCandidate(MemoryFragment fragment, double score) {}

    record RankedRecallCandidate(MemoryFragment fragment, double score, String tier) {}

    private enum RecallCandidateSource {
        L2_SEARCH,
        L2_NAMESPACE_FALLBACK
    }

    private static final class RecallDiagnosticsAccumulator {
        private final List<String> requiredTags;
        private int l1CandidateCount;
        private int l1TagMatchedCount;
        private int l1SelectedCount;
        private int l1TokenBudgetRejectedCount;
        private int l2SearchCandidateCount;
        private int l2SearchAcceptedCount;
        private int l2SearchDuplicateRejectedCount;
        private int l2SearchTagRejectedCount;
        private int l2SearchTokenBudgetRejectedCount;
        private int l2NamespaceFallbackCandidateCount;
        private int l2NamespaceFallbackAcceptedCount;
        private int l2NamespaceFallbackDuplicateRejectedCount;
        private int l2NamespaceFallbackTagRejectedCount;
        private int l2NamespaceFallbackTokenBudgetRejectedCount;
        private int findFragmentL1HitCount;
        private int findFragmentL3HitCount;
        private int findFragmentL2HitCount;
        private int findFragmentMissCount;
        private int enrichFragmentTagMatchedCount;
        private int enrichCandidateTagMatchedCount;
        private int enrichL2TagFallbackMatchedCount;
        private int enrichTagRejectedCount;
        private int finalReturnedCount;
        private String emptyRecallReason;

        private RecallDiagnosticsAccumulator(List<String> requiredTags) {
            this.requiredTags = requiredTags == null ? List.of() : List.copyOf(requiredTags);
        }

        private void setL1CandidateCount(int l1CandidateCount) {
            this.l1CandidateCount = l1CandidateCount;
        }

        private void setL1TagMatchedCount(int l1TagMatchedCount) {
            this.l1TagMatchedCount = l1TagMatchedCount;
        }

        private void incrementL1SelectedCount() {
            l1SelectedCount++;
        }

        private void incrementL1TokenBudgetRejectedCount() {
            l1TokenBudgetRejectedCount++;
        }

        private void recordCandidateCount(RecallCandidateSource source, int count) {
            if (source == RecallCandidateSource.L2_SEARCH) {
                l2SearchCandidateCount = count;
                return;
            }
            l2NamespaceFallbackCandidateCount = count;
        }

        private void incrementAcceptedCount(RecallCandidateSource source) {
            if (source == RecallCandidateSource.L2_SEARCH) {
                l2SearchAcceptedCount++;
                return;
            }
            l2NamespaceFallbackAcceptedCount++;
        }

        private void incrementDuplicateRejectedCount(RecallCandidateSource source) {
            if (source == RecallCandidateSource.L2_SEARCH) {
                l2SearchDuplicateRejectedCount++;
                return;
            }
            l2NamespaceFallbackDuplicateRejectedCount++;
        }

        private void incrementTagRejectedCount(RecallCandidateSource source) {
            if (source == RecallCandidateSource.L2_SEARCH) {
                l2SearchTagRejectedCount++;
                return;
            }
            l2NamespaceFallbackTagRejectedCount++;
        }

        private void incrementTokenBudgetRejectedCount(RecallCandidateSource source) {
            if (source == RecallCandidateSource.L2_SEARCH) {
                l2SearchTokenBudgetRejectedCount++;
                return;
            }
            l2NamespaceFallbackTokenBudgetRejectedCount++;
        }

        private void incrementFindFragmentL1HitCount() {
            findFragmentL1HitCount++;
        }

        private void incrementFindFragmentL3HitCount() {
            findFragmentL3HitCount++;
        }

        private void incrementFindFragmentL2HitCount() {
            findFragmentL2HitCount++;
        }

        private void incrementFindFragmentMissCount() {
            findFragmentMissCount++;
        }

        private void incrementEnrichFragmentTagMatchedCount() {
            enrichFragmentTagMatchedCount++;
        }

        private void incrementEnrichCandidateTagMatchedCount() {
            enrichCandidateTagMatchedCount++;
        }

        private void incrementEnrichL2TagFallbackMatchedCount() {
            enrichL2TagFallbackMatchedCount++;
        }

        private void incrementEnrichTagRejectedCount() {
            enrichTagRejectedCount++;
        }

        private void setFinalReturnedCount(int finalReturnedCount) {
            this.finalReturnedCount = finalReturnedCount;
        }

        private void resolveEmptyRecallReason() {
            if (finalReturnedCount > 0) {
                emptyRecallReason = null;
                return;
            }
            if (l1CandidateCount == 0 && l2SearchCandidateCount == 0 && l2NamespaceFallbackCandidateCount == 0) {
                emptyRecallReason = "NO_CANDIDATES_FOUND";
                return;
            }
            if (totalTokenBudgetRejectedCount() > 0
                    && totalAcceptedCount() == 0
                    && totalTagRejectedCount() == 0) {
                emptyRecallReason = "TOKEN_BUDGET_EXHAUSTED";
                return;
            }
            if (totalTagRejectedCount() > 0 && totalAcceptedCount() == 0) {
                emptyRecallReason = "TAG_FILTER_REJECTED";
                return;
            }
            if (l2SearchCandidateCount > 0 || l2NamespaceFallbackCandidateCount > 0) {
                emptyRecallReason = "L2_ENRICHMENT_EMPTY";
                return;
            }
            if (l1TagMatchedCount > 0 && l1SelectedCount == 0) {
                emptyRecallReason = "L1_SELECTION_EMPTY";
                return;
            }
            emptyRecallReason = "NO_SELECTION";
        }

        private int totalAcceptedCount() {
            return l1SelectedCount + l2SearchAcceptedCount + l2NamespaceFallbackAcceptedCount;
        }

        private int totalTagRejectedCount() {
            return l2SearchTagRejectedCount + l2NamespaceFallbackTagRejectedCount;
        }

        private int totalTokenBudgetRejectedCount() {
            return l1TokenBudgetRejectedCount
                    + l2SearchTokenBudgetRejectedCount
                    + l2NamespaceFallbackTokenBudgetRejectedCount;
        }

        private RecallDiagnostics toDiagnostics() {
            return RecallDiagnostics.builder()
                    .requiredTags(requiredTags)
                    .l1CandidateCount(l1CandidateCount)
                    .l1TagMatchedCount(l1TagMatchedCount)
                    .l1SelectedCount(l1SelectedCount)
                    .l1TokenBudgetRejectedCount(l1TokenBudgetRejectedCount)
                    .l2SearchCandidateCount(l2SearchCandidateCount)
                    .l2SearchAcceptedCount(l2SearchAcceptedCount)
                    .l2SearchDuplicateRejectedCount(l2SearchDuplicateRejectedCount)
                    .l2SearchTagRejectedCount(l2SearchTagRejectedCount)
                    .l2SearchTokenBudgetRejectedCount(l2SearchTokenBudgetRejectedCount)
                    .l2NamespaceFallbackCandidateCount(l2NamespaceFallbackCandidateCount)
                    .l2NamespaceFallbackAcceptedCount(l2NamespaceFallbackAcceptedCount)
                    .l2NamespaceFallbackDuplicateRejectedCount(l2NamespaceFallbackDuplicateRejectedCount)
                    .l2NamespaceFallbackTagRejectedCount(l2NamespaceFallbackTagRejectedCount)
                    .l2NamespaceFallbackTokenBudgetRejectedCount(l2NamespaceFallbackTokenBudgetRejectedCount)
                    .findFragmentL1HitCount(findFragmentL1HitCount)
                    .findFragmentL3HitCount(findFragmentL3HitCount)
                    .findFragmentL2HitCount(findFragmentL2HitCount)
                    .findFragmentMissCount(findFragmentMissCount)
                    .enrichFragmentTagMatchedCount(enrichFragmentTagMatchedCount)
                    .enrichCandidateTagMatchedCount(enrichCandidateTagMatchedCount)
                    .enrichL2TagFallbackMatchedCount(enrichL2TagFallbackMatchedCount)
                    .enrichTagRejectedCount(enrichTagRejectedCount)
                    .finalReturnedCount(finalReturnedCount)
                    .emptyRecallReason(emptyRecallReason)
                    .build();
        }
    }
}
