package com.vortex.kernel.hmc;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.l1.CaffeineHotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hierarchical Memory Controller (HMC).
 *
 * Orchestrates the three-tier memory pipeline:
 *   L1 (Caffeine) → L2 (Milvus) → L3 (MinIO)
 *
 * Key flows:
 *   store()  — write to L1; async propagate to L2/L3
 *   recall() — L1 hit → return; L1 miss → L2 search → prefetch to L1
 *   evict()  — proactive Semantic-LRU eviction when L1 is near capacity
 */
@Slf4j
@Service
public class HierarchicalMemoryController {

    private final L1HotStore l1;
    private final L2WarmStore l2;
    private final L3ColdStore l3;
    private final SemanticEvictionPolicy evictionPolicy;
    private final NamespaceQuotaManager namespaceQuotaManager;
    private final AdaptiveWeightLearner adaptiveWeightLearner;
    private final EvictionDecisionLogger evictionDecisionLogger;
    private final EvictionRegretTracker regretTracker;
    private final MemorySloTracker sloTracker;
    private final FragmentPersistenceManager persistenceManager;
    private final SemanticTextSplitter splitter;
    private final PriorityBlockingQueue<PinnedFragmentRef> pinExpirations = new PriorityBlockingQueue<>();
    private final ConcurrentMap<String, Long> pinnedFragmentDeadlines = new ConcurrentHashMap<>();
    private final AtomicBoolean clearingExpiredPins = new AtomicBoolean(false);

    /** BGE-Small: always available, used for L1 fast scoring. */
    private final EmbeddingService l1EmbeddingService;

    /**
     * Cloud embedding (DeepSeek): optional, used for L2 Milvus upsert/search.
     * Null when vortex.kernel.embedding.cloud.enabled=false.
     * Falls back to l1EmbeddingService when null.
     */
    private final EmbeddingService l2EmbeddingService;

    /** Fraction of L1 capacity that triggers proactive eviction. */
    private final double evictionThreshold;

    public HierarchicalMemoryController(
            L1HotStore l1,
            L2WarmStore l2,
            L3ColdStore l3,
            SemanticEvictionPolicy evictionPolicy,
            NamespaceQuotaManager namespaceQuotaManager,
            AdaptiveWeightLearner adaptiveWeightLearner,
            EvictionDecisionLogger evictionDecisionLogger,
            EvictionRegretTracker regretTracker,
            MemorySloTracker sloTracker,
            FragmentPersistenceManager persistenceManager,
            SemanticTextSplitter splitter,
            @Qualifier("bgeSmallEmbeddingService") EmbeddingService l1EmbeddingService,
            @Qualifier("cloudEmbeddingService") ObjectProvider<EmbeddingService> cloudEmbeddingProvider,
            double evictionThreshold) {
        this(
                l1,
                l2,
                l3,
                evictionPolicy,
                namespaceQuotaManager,
                adaptiveWeightLearner,
                evictionDecisionLogger,
                regretTracker,
                sloTracker,
                persistenceManager,
                splitter,
                l1EmbeddingService,
                cloudEmbeddingProvider,
                evictionThreshold,
                30_000L);
    }

    public HierarchicalMemoryController(
            L1HotStore l1,
            L2WarmStore l2,
            L3ColdStore l3,
            SemanticEvictionPolicy evictionPolicy,
            NamespaceQuotaManager namespaceQuotaManager,
            AdaptiveWeightLearner adaptiveWeightLearner,
            EvictionDecisionLogger evictionDecisionLogger,
            EvictionRegretTracker regretTracker,
            MemorySloTracker sloTracker,
            FragmentPersistenceManager persistenceManager,
            SemanticTextSplitter splitter,
            @Qualifier("bgeSmallEmbeddingService") EmbeddingService l1EmbeddingService,
            @Qualifier("cloudEmbeddingService") ObjectProvider<EmbeddingService> cloudEmbeddingProvider,
            @Value("${vortex.kernel.eviction.threshold:0.85}") double evictionThreshold,
            @Value("${vortex.kernel.pin.cleanup-interval-ms:30000}") long pinCleanupIntervalMillis) {
        this.l1 = l1;
        this.l2 = l2;
        this.l3 = l3;
        this.evictionPolicy = evictionPolicy;
        this.namespaceQuotaManager = namespaceQuotaManager;
        this.adaptiveWeightLearner = adaptiveWeightLearner;
        this.evictionDecisionLogger = evictionDecisionLogger;
        this.regretTracker = regretTracker;
        this.sloTracker = sloTracker;
        this.persistenceManager = persistenceManager;
        this.splitter = splitter;
        this.l1EmbeddingService = l1EmbeddingService;
        this.l2EmbeddingService = cloudEmbeddingProvider.getIfAvailable();
        this.evictionThreshold = evictionThreshold;
        if (l1 instanceof CaffeineHotStore caffeineStore) {
            caffeineStore.setEvictionListener(this::handleCaffeineEviction);
        }

        if (this.l2EmbeddingService != null) {
            log.info("HMC initialized: L1=BGE-Small({}d), L2=DeepSeek({}d)",
                    l1EmbeddingService.dimension(), l2EmbeddingService.dimension());
        } else {
            log.info("HMC initialized: L1=L2=BGE-Small({}d) [cloud embedding disabled]",
                    l1EmbeddingService.dimension());
        }

        validateL2DimensionCompatibility();
    }

    @PostConstruct
    void cleanPinsOnStartup() {
        rebuildPinIndex();
        clearExpiredPins();
    }

    // ---- Public API ----

    /**
     * Store a raw text fragment.
     * The text is split at semantic boundaries, each chunk stored in L1.
     * Async propagation to L2/L3 happens in the background.
     *
     * @param content   raw text
     * @param namespace agent/session namespace
     * @param tags      optional tags
     * @return list of created fragment IDs
     */
    public List<String> store(
            String content,
            String namespace,
            List<String> tags,
            String reasoningChainId,
            Long pinTtlMillis) {
        List<MemoryFragment> chunks = splitter.split(content, namespace, tags, reasoningChainId, pinTtlMillis);
        List<String> ids = new ArrayList<>();
        for (MemoryFragment chunk : chunks) {
            storeFragment(chunk);
            ids.add(chunk.getId());
        }
        return ids;
    }

    /**
     * Store a pre-built fragment.
     * Generates L1 embedding (BGE-Small) synchronously so eviction scoring works immediately.
     * When cloud embedding is enabled, also generates L2 embedding (DeepSeek) for Milvus.
     */
    public void storeFragment(MemoryFragment fragment) {
        long startedAt = System.nanoTime();
        // L1 embedding — BGE-Small, always synchronous
        if (fragment.getEmbedding() == null) {
            fragment.setEmbedding(l1EmbeddingService.embed(fragment.getContent()));
        }
        // L2 embedding — DeepSeek, when cloud is enabled
        if (l2EmbeddingService != null && fragment.getL2Embedding() == null) {
            fragment.setL2Embedding(l2EmbeddingService.embed(fragment.getContent()));
        }
        enforceQuotaBeforeInsert(fragment);
        enforceGlobalCapacityBeforeInsert(fragment);
        maybeEvict(fragment.getNamespace(), fragment.getEmbedding());
        tryAdmitToL1(fragment, "initial-store");
        // Async: persist to L2 and L3
        persistenceManager.persistAsync(fragment, "initial-store");
        sloTracker.recordStoreLatency(System.nanoTime() - startedAt);
    }

    /**
     * Recall semantically relevant fragments for a query.
     *
     * Strategy:
     *   1. Embed query with BGE-Small → score all L1 fragments by cosine similarity.
     *   2. If cloud embedding is enabled, also embed query with DeepSeek → search L2 Milvus.
     *   3. Prefetch L2 results back into L1 for subsequent calls.
     */
    public RecallResult recall(RecallQuery query) {
        long startedAt = System.nanoTime();
        List<String> requiredTags = normalizeTags(query.getTags());
        MemoryScenario scenario = query.getScenario() == null ? MemoryScenario.CHAT : query.getScenario();
        AdaptiveWeightLearner.ProfileSelection profileSelection = adaptiveWeightLearner.selectProfiles(scenario);
        AdaptiveWeightProfile baselineProfile = evictionPolicy.defaultProfile();
        // L1 query embedding — BGE-Small (fast, local)
        float[] l1QueryEmbedding = l1EmbeddingService.embed(query.getQuery());
        // L2 query embedding — DeepSeek if available, else reuse BGE-Small
        float[] l2QueryEmbedding = (l2EmbeddingService != null)
                ? l2EmbeddingService.embed(query.getQuery())
                : l1QueryEmbedding;

        List<RecallResult.ScoredFragment> results = new ArrayList<>();
        int tokensSoFar = 0;

        // --- L1: score all fragments by cosine similarity, take top-k ---
        List<MemoryFragment> l1Candidates = l1.getAll(query.getNamespace());
        List<MemoryFragment> filteredL1Candidates = l1Candidates.stream()
                .filter(fragment -> matchesAllTags(fragment, requiredTags))
                .toList();
        List<ScoredCandidate> activeRanked = rankForRecall(
                filteredL1Candidates,
                l1QueryEmbedding,
                profileSelection.active());
        List<ScoredCandidate> shadowRanked = rankForRecall(
                filteredL1Candidates,
                l1QueryEmbedding,
                profileSelection.shadow());
        List<ScoredCandidate> baselineRanked = rankForRecall(
                filteredL1Candidates,
                l1QueryEmbedding,
                baselineProfile);

        for (ScoredCandidate sc : activeRanked) {
            if (results.size() >= query.getTopK()) break;
            if (tokensSoFar + sc.fragment().getTokenCount() > query.getTokenBudget()) continue;
            MemoryFragment recalled = refreshL1Fragment(sc.fragment());
            results.add(RecallResult.ScoredFragment.builder()
                    .fragment(recalled).score(sc.score()).tier("L1").build());
            tokensSoFar += recalled.getTokenCount();
        }

        // --- L2: semantic search if L1 didn't fill the budget ---
        if (results.size() < query.getTopK()) {
            Set<String> seenIds = results.stream()
                    .map(sf -> sf.getFragment().getId())
                    .collect(Collectors.toSet());
            int needed = query.getTopK() - results.size();
            int l2SearchLimit = requiredTags.isEmpty() ? needed : Math.max(needed * 4, needed);
            List<MemoryFragment> l2Hits = l2.search(
                    l2QueryEmbedding, query.getNamespace(), l2SearchLimit);
            IncrementalRedundancyState redundancyState = IncrementalRedundancyState.from(filteredL1Candidates);
            for (MemoryFragment f : l2Hits) {
                if (results.size() >= query.getTopK()) break;
                if (seenIds.contains(f.getId())) continue;
                MemoryFragment candidate = enrichForRecall(f, requiredTags);
                if (candidate == null) continue;
                if (tokensSoFar + candidate.getTokenCount() > query.getTokenBudget()) continue;
                // Re-embed with BGE-Small so L1 eviction scoring works correctly
                if (candidate.getEmbedding() == null) {
                    candidate.setEmbedding(l1EmbeddingService.embed(candidate.getContent()));
                }
                candidate.reinforceImportanceOnRecall();
                enforceGlobalCapacityBeforeInsert(candidate);
                maybeEvict(candidate.getNamespace(), candidate.getEmbedding());
                // Prefetch back to L1 for future calls
                tryAdmitToL1(candidate, "recall-reinforcement");
                persistenceManager.persistAsync(candidate, "recall-reinforcement");
                regretTracker.recordRecall(candidate, "L2");
                redundancyState.add(candidate);
                double score = scoreForRecall(
                        candidate,
                        l1QueryEmbedding,
                        profileSelection.active(),
                        redundancyState.snapshot());
                results.add(RecallResult.ScoredFragment.builder()
                        .fragment(candidate).score(score).tier("L2").build());
                seenIds.add(candidate.getId());
                tokensSoFar += candidate.getTokenCount();
                filteredL1Candidates = appendCandidate(filteredL1Candidates, candidate);
            }
        }

        List<String> trace = results.stream().map(RecallResult.ScoredFragment::getTier).toList();
        String recallSessionId = adaptiveWeightLearner.recordRecallSession(RecallSessionRecord.builder()
                .namespace(query.getNamespace())
                .scenario(scenario)
                .activeProfileName(profileSelection.active().getProfileName())
                .shadowProfileName(profileSelection.shadow().getProfileName())
                .rankedFragmentIds(results.stream().map(sf -> sf.getFragment().getId()).toList())
                .shadowRankedFragmentIds(shadowRanked.stream().map(sc -> sc.fragment().getId()).toList())
                .baselineRankedFragmentIds(baselineRanked.stream().map(sc -> sc.fragment().getId()).toList())
                .createdAt(java.time.Instant.now())
                .build());
        EvictionRegretTracker.RegretSnapshot regretSnapshot = regretTracker.snapshot();
        sloTracker.recordRegretRate(regretSnapshot.regretRate());
        ShadowEvaluationTracker.ShadowEvaluationSnapshot learningSnapshot =
                adaptiveWeightLearner.snapshot(scenario).shadowEvaluation();
        sloTracker.recordLearningLift(
                learningSnapshot.relativeLift(),
                learningSnapshot.baselineRelativeLift());
        sloTracker.recordRecallLatency(System.nanoTime() - startedAt);
        return RecallResult.builder()
                .fragments(results)
                .totalTokens(tokensSoFar)
                .sourceTrace(trace)
                .recallSessionId(recallSessionId)
                .activeProfileName(profileSelection.active().getProfileName())
                .shadowProfileName(profileSelection.shadow().getProfileName())
                .build();
    }

    public void recordFeedback(MemoryFeedbackRequest feedbackRequest) {
        AdaptiveWeightLearner.LearningSnapshot snapshot = adaptiveWeightLearner.recordFeedback(
                feedbackRequest.getRecallSessionId(),
                feedbackRequest.getUsedFragmentIds() == null
                        ? Set.of()
                        : new HashSet<>(feedbackRequest.getUsedFragmentIds()),
                feedbackRequest.isAnswerAccepted(),
                regretTracker.snapshot().regretRate());
        if (snapshot != null) {
            sloTracker.recordLearningLift(
                    snapshot.shadowEvaluation().relativeLift(),
                    snapshot.shadowEvaluation().baselineRelativeLift());
        }
    }

    public AdaptiveWeightLearner.LearningSnapshot learningSnapshot(MemoryScenario scenario) {
        return adaptiveWeightLearner.snapshot(scenario);
    }

    public MemorySloTracker.SloSnapshot sloSnapshot() {
        return sloTracker.snapshot();
    }

    public Optional<MemoryFragment> pinFragment(String fragmentId, long ttlMillis) {
        if (fragmentId == null || fragmentId.isBlank() || ttlMillis <= 0) {
            return Optional.empty();
        }
        Optional<MemoryFragment> fragment = findFragment(fragmentId);
        fragment.ifPresent(found -> {
            found.pinForMillis(ttlMillis);
            l1.put(found, false);
            indexPin(found);
            persistenceManager.persistAsync(found, "pin-update");
        });
        return fragment;
    }

    public Optional<MemoryFragment> unpinFragment(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) {
            return Optional.empty();
        }
        Optional<MemoryFragment> fragment = findFragment(fragmentId);
        fragment.ifPresent(found -> {
            found.unpin();
            l1.put(found, false);
            indexPin(found);
            persistenceManager.persistAsync(found, "pin-update");
        });
        return fragment;
    }

    @Scheduled(fixedDelayString = "${vortex.kernel.pin.cleanup-interval-ms:30000}")
    public void clearExpiredPins() {
        if (!clearingExpiredPins.compareAndSet(false, true)) {
            return;
        }
        try {
        long now = System.currentTimeMillis();
        int cleared = 0;
        while (true) {
            PinnedFragmentRef ref = pinExpirations.peek();
            if (ref == null || ref.pinnedUntilEpochMillis() > now) {
                break;
            }
            pinExpirations.poll();
            Long currentDeadline = pinnedFragmentDeadlines.get(ref.fragmentId());
            if (currentDeadline == null || currentDeadline.longValue() != ref.pinnedUntilEpochMillis()) {
                continue;
            }
            Optional<MemoryFragment> fragment = findFragment(ref.fragmentId());
            if (fragment.isEmpty()) {
                pinnedFragmentDeadlines.remove(ref.fragmentId(), currentDeadline);
                continue;
            }
            MemoryFragment found = fragment.get();
            if (!found.clearExpiredPin()) {
                indexPin(found);
                continue;
            }
            l1.put(found, false);
            indexPin(found);
            persistenceManager.persistAsync(found, "pin-expired");
            cleared++;
        }
        if (cleared > 0) {
            log.debug("Cleared expired pins count={}", cleared);
        }
        } finally {
            clearingExpiredPins.set(false);
        }
    }

    private record ScoredCandidate(MemoryFragment fragment, double score) {}

    /**
     * Proactively evict low-score fragments from L1 when approaching capacity.
     * Called before each store() to keep L1 healthy.
     */
    public void maybeEvict(String namespace, float[] queryEmbedding) {
        clearExpiredPins();
        if (namespace == null || namespace.isBlank()) return;
        long current = l1.currentTokenCount();
        long max = l1.maxTokenCapacity();
        if (max == 0 || (double) current / max < evictionThreshold) return;

        List<MemoryFragment> candidates = new ArrayList<>(l1.getAll(namespace));
        if (candidates.isEmpty()) return;

        long targetEvict = Math.max(1L, (long) Math.ceil(max * 0.10));
        List<SemanticEvictionPolicy.EvictionCandidate> toEvict = evictionPolicy.selectDetailedForEviction(
                candidates, queryEmbedding, targetEvict);
        long evictedTokens = 0;
        Set<String> evictedGroups = new HashSet<>();
        Map<String, Long> namespaceTokenUsage = computeNamespaceTokenUsage(candidates);
        for (SemanticEvictionPolicy.EvictionCandidate candidate : toEvict) {
            evictedTokens += evictCandidateGroup(
                    candidate,
                    namespace,
                    targetEvict,
                    "semantic",
                    evictedGroups,
                    0L,
                    namespaceTokenUsage);
            if (evictedTokens >= targetEvict) break;
        }
        log.debug("Proactive eviction: namespace={} removed {} fragments ({} tokens)",
                namespace, toEvict.size(), evictedTokens);
    }

    /** Expose L1 store for monitoring (e.g., health endpoints). */
    public L1HotStore getL1() {
        return l1;
    }

    // ---- Internal helpers ----

    private MemoryFragment refreshL1Fragment(MemoryFragment fragment) {
        MemoryFragment refreshed = l1.get(fragment.getId()).orElse(fragment);
        refreshed.clearExpiredPin();
        refreshed.reinforceImportanceOnRecall();
        return refreshed;
    }

    private void enforceQuotaBeforeInsert(MemoryFragment incomingFragment) {
        clearExpiredPins();
        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return;
        }
        List<MemoryFragment> allFragments = new ArrayList<>(caffeineStore.getAllFragments());
        NamespaceQuotaManager.QuotaSnapshot snapshot = namespaceQuotaManager.snapshot(
                allFragments,
                l1.maxTokenCapacity(),
                incomingFragment.getNamespace());
        long projectedUsage = snapshot.focusNamespaceUsage() + incomingFragment.getTokenCount();
        if (projectedUsage <= snapshot.hardQuotaPerNamespace()) {
            return;
        }

        long requiredTokens = projectedUsage - snapshot.hardQuotaPerNamespace();
        List<SemanticEvictionPolicy.EvictionCandidate> ownCandidates = evictionPolicy.rankCandidates(
                l1.getAll(incomingFragment.getNamespace()), incomingFragment.getEmbedding());
        long released = evictCandidatesUntil(
                ownCandidates,
                incomingFragment.getNamespace(),
                requiredTokens,
                "quota-self-reclaim",
                0L);
        if (released >= requiredTokens) {
            return;
        }

        long remainingRequired = requiredTokens - released;
        for (String otherNamespace : namespaceQuotaManager.evictionPriorityNamespaces(
                allFragments, l1.maxTokenCapacity(), incomingFragment.getNamespace())) {
            List<MemoryFragment> namespaceFragments = l1.getAll(otherNamespace);
            NamespaceQuotaManager.QuotaSnapshot currentSnapshot = namespaceQuotaManager.snapshot(
                    allFragments,
                    l1.maxTokenCapacity(),
                    otherNamespace);
            long borrowedTokens = Math.max(0L,
                    namespaceFragments.stream().mapToLong(MemoryFragment::getTokenCount).sum()
                            - currentSnapshot.hardQuotaPerNamespace());
            if (borrowedTokens <= 0) {
                continue;
            }
            List<SemanticEvictionPolicy.EvictionCandidate> ranked = evictionPolicy.rankCandidates(
                    namespaceFragments, incomingFragment.getEmbedding());
            long evicted = evictCandidatesUntil(
                    ranked,
                    otherNamespace,
                    Math.min(remainingRequired, borrowedTokens),
                    "quota-borrow-reclaim",
                    currentSnapshot.hardQuotaPerNamespace());
            remainingRequired -= evicted;
            if (remainingRequired <= 0) {
                break;
            }
        }
    }

    private void enforceGlobalCapacityBeforeInsert(MemoryFragment incomingFragment) {
        clearExpiredPins();
        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return;
        }
        long overflow = (caffeineStore.currentTokenCount() + incomingFragment.getTokenCount())
                - l1.maxTokenCapacity();
        if (overflow <= 0) {
            return;
        }

        List<SemanticEvictionPolicy.EvictionCandidate> localCandidates = evictionPolicy.rankCandidates(
                l1.getAll(incomingFragment.getNamespace()),
                incomingFragment.getEmbedding());
        long released = evictCandidatesUntil(
                localCandidates,
                incomingFragment.getNamespace(),
                overflow,
                "capacity-self-reclaim");
        if (released >= overflow) {
            return;
        }

        long remaining = overflow - released;
        List<MemoryFragment> allFragments = new ArrayList<>(caffeineStore.getAllFragments());
        List<SemanticEvictionPolicy.EvictionCandidate> globalCandidates = evictionPolicy.rankCandidates(
                allFragments.stream()
                        .filter(fragment -> !Objects.equals(fragment.getNamespace(), incomingFragment.getNamespace()))
                        .toList(),
                incomingFragment.getEmbedding());
        evictCandidatesUntil(
                globalCandidates,
                incomingFragment.getNamespace(),
                remaining,
                "capacity-global-reclaim");
    }

    private void enforceGlobalCapacityAfterInsert(MemoryFragment anchorFragment) {
        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return;
        }
        long overflow = caffeineStore.currentTokenCount() - l1.maxTokenCapacity();
        if (overflow <= 0) {
            return;
        }
        List<SemanticEvictionPolicy.EvictionCandidate> candidates = evictionPolicy.rankCandidates(
                caffeineStore.getAllFragments().stream()
                        .filter(fragment -> !Objects.equals(fragment.getId(), anchorFragment.getId()))
                        .toList(),
                anchorFragment.getEmbedding());
        long released = evictCandidatesUntil(
                candidates,
                anchorFragment.getNamespace(),
                overflow,
                "capacity-post-insert-reclaim");
        if (released < overflow) {
            l1.remove(anchorFragment.getId());
            pinnedFragmentDeadlines.remove(anchorFragment.getId());
            log.warn(
                    "Rejected L1 admission after insert due to saturated pinned working set fragmentId={} namespace={} overflow={} released={}",
                    anchorFragment.getId(),
                    anchorFragment.getNamespace(),
                    overflow,
                    released);
        }
    }

    private void tryAdmitToL1(MemoryFragment fragment, String context) {
        long projectedTokens = l1.currentTokenCount() + fragment.getTokenCount();
        if (projectedTokens > l1.maxTokenCapacity()) {
            log.warn(
                    "Skipped L1 admission due to saturated pinned working set fragmentId={} namespace={} context={} projectedTokens={} capacity={}",
                    fragment.getId(),
                    fragment.getNamespace(),
                    context,
                    projectedTokens,
                    l1.maxTokenCapacity());
            return;
        }
        l1.put(fragment);
        indexPin(fragment);
        enforceGlobalCapacityAfterInsert(fragment);
    }

    private long evictCandidatesUntil(
            List<SemanticEvictionPolicy.EvictionCandidate> candidates,
            String triggerNamespace,
            long targetTokens,
            String reason) {
        return evictCandidatesUntil(candidates, triggerNamespace, targetTokens, reason, 0L);
    }

    private long evictCandidatesUntil(
            List<SemanticEvictionPolicy.EvictionCandidate> candidates,
            String triggerNamespace,
            long targetTokens,
            String reason,
            long minRemainingTokens) {
        Set<String> evictedGroups = new HashSet<>();
        long evictedTokens = 0;
        Map<String, Long> namespaceTokenUsage = computeNamespaceTokenUsage(
                candidates.stream().map(SemanticEvictionPolicy.EvictionCandidate::fragment).toList());
        for (SemanticEvictionPolicy.EvictionCandidate candidate : candidates) {
            evictedTokens += evictCandidateGroup(
                    candidate,
                    triggerNamespace,
                    targetTokens,
                    reason,
                    evictedGroups,
                    minRemainingTokens,
                    namespaceTokenUsage);
            if (evictedTokens >= targetTokens) {
                break;
            }
        }
        return evictedTokens;
    }

    private long evictCandidateGroup(
            SemanticEvictionPolicy.EvictionCandidate candidate,
            String triggerNamespace,
            long targetTokens,
            String reason,
            Set<String> evictedGroups,
            long minRemainingTokens,
            Map<String, Long> namespaceTokenUsage) {
        if (candidate.pinned()) {
            return 0;
        }
        String groupId = candidate.reasoningChainId();
        if (groupId != null && !groupId.isBlank() && !evictedGroups.add(groupId)) {
            return 0;
        }

        List<MemoryFragment> evictionGroup = resolveEvictionGroup(candidate);
        if (evictionGroup.isEmpty()) {
            return 0;
        }
        String namespace = candidate.fragment().getNamespace();
        long currentNamespaceTokens = namespaceTokenUsage.getOrDefault(namespace, 0L);
        long groupTokens = evictionGroup.stream()
                .mapToLong(MemoryFragment::getTokenCount)
                .sum();
        if (currentNamespaceTokens - groupTokens < minRemainingTokens) {
            return 0;
        }
        long released = 0;
        for (MemoryFragment fragment : evictionGroup) {
            SemanticEvictionPolicy.EvictionCandidate scored = evictionPolicy.scoreFragment(fragment, candidate.fragment().getEmbedding());
            evictionDecisionLogger.logSemanticDecision(scored, triggerNamespace, targetTokens);
            regretTracker.recordEviction(fragment, reason);
            l1.remove(fragment.getId());
            pinnedFragmentDeadlines.remove(fragment.getId());
            persistenceManager.persistAsync(fragment, reason);
            released += fragment.getTokenCount();
        }
        long releasedTokens = released;
        namespaceTokenUsage.compute(namespace, (key, value) -> Math.max(0L, (value == null ? 0L : value) - releasedTokens));
        return released;
    }

    private List<MemoryFragment> resolveEvictionGroup(SemanticEvictionPolicy.EvictionCandidate candidate) {
        String groupId = candidate.reasoningChainId();
        if (groupId == null || groupId.isBlank()) {
            MemoryFragment fragment = l1.peek(candidate.fragment().getId()).orElse(candidate.fragment());
            if (fragment.clearExpiredPin() || fragment.isPinned()) {
                if (fragment.isPinned()) {
                    return List.of();
                }
                l1.put(fragment, false);
                indexPin(fragment);
            }
            return fragment.isPinned() ? List.of() : List.of(fragment);
        }
        return l1.getAll(candidate.fragment().getNamespace()).stream()
                .map(fragment -> {
                    if (fragment.clearExpiredPin()) {
                        l1.put(fragment, false);
                        indexPin(fragment);
                    }
                    return fragment;
                })
                .filter(fragment -> groupId.equals(fragment.getReasoningChainId()))
                .filter(fragment -> !fragment.isPinned())
                .toList();
    }

    private void handleCaffeineEviction(MemoryFragment fragment, RemovalCause cause) {
        SemanticEvictionPolicy.EvictionCandidate candidate = evictionPolicy.scoreFragment(fragment, null);
        evictionDecisionLogger.logFallbackEviction(candidate, fragment.getNamespace(), cause.name());
        regretTracker.recordEviction(fragment, "caffeine-" + cause.name().toLowerCase(Locale.ROOT));
        persistenceManager.persistAsync(fragment, "caffeine-" + cause.name().toLowerCase(Locale.ROOT));
    }

    private List<ScoredCandidate> rankForRecall(
            List<MemoryFragment> candidates,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile) {
        Map<String, RedundancyStats> redundancyStats = computeRedundancyStats(candidates);
        return candidates.stream()
                .map(fragment -> new ScoredCandidate(
                        fragment,
                        scoreForRecall(fragment, queryEmbedding, profile, redundancyStats)))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
    }

    private double scoreForRecall(
            MemoryFragment fragment,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile,
            Map<String, RedundancyStats> redundancyStats) {
        RedundancyStats stats = redundancyStats.getOrDefault(fragment.getId(), new RedundancyStats(0.0, 0.0));
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

    private static double cosineSimilarity(float[] a, float[] b) {
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

    private void validateL2DimensionCompatibility() {
        int configuredL2Dimension = l2.vectorDimension();
        if (configuredL2Dimension <= 0) {
            return;
        }

        int effectiveL2Dimension = (l2EmbeddingService != null)
                ? l2EmbeddingService.dimension()
                : l1EmbeddingService.dimension();

        if (configuredL2Dimension != effectiveL2Dimension) {
            String source = (l2EmbeddingService != null) ? "cloud embedding" : "local fallback embedding";
            throw new IllegalStateException(
                    "L2 vector dimension mismatch: store expects " + configuredL2Dimension
                            + " but " + source + " produces " + effectiveL2Dimension
                            + ". Align vortex.storage.l2.embedding-dim with the active embedding path.");
        }
    }

    private MemoryFragment enrichForRecall(MemoryFragment candidate, List<String> requiredTags) {
        MemoryFragment fragment = findFragment(candidate.getId()).orElse(candidate);
        return matchesAllTags(fragment, requiredTags) ? fragment : null;
    }

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

    private Optional<MemoryFragment> findFragment(String fragmentId) {
        Optional<MemoryFragment> l1Fragment = l1.peek(fragmentId);
        if (l1Fragment.isPresent()) {
            return l1Fragment;
        }
        Optional<MemoryFragment> archived = l3.retrieveFragment(fragmentId);
        if (archived.isPresent()) {
            MemoryFragment fragment = archived.get();
            fragment.clearExpiredPin();
            if (fragment.getEmbedding() == null) {
                fragment.setEmbedding(l1EmbeddingService.embed(fragment.getContent()));
            }
            if (l2EmbeddingService != null && fragment.getL2Embedding() == null) {
                fragment.setL2Embedding(l2EmbeddingService.embed(fragment.getContent()));
            }
            return Optional.of(fragment);
        }
        return l2.get(fragmentId).map(fragment -> {
            if (fragment.getEmbedding() == null) {
                fragment.setEmbedding(l1EmbeddingService.embed(fragment.getContent()));
            }
            return fragment;
        });
    }

    private void rebuildPinIndex() {
        pinnedFragmentDeadlines.clear();
        pinExpirations.clear();
        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return;
        }
        caffeineStore.getAllFragments().forEach(this::indexPin);
    }

    private void indexPin(MemoryFragment fragment) {
        Long pinnedUntil = fragment.getPinnedUntil();
        if (pinnedUntil == null) {
            pinnedFragmentDeadlines.remove(fragment.getId());
            return;
        }
        if (pinnedUntil <= System.currentTimeMillis()) {
            fragment.clearExpiredPin();
            pinnedFragmentDeadlines.remove(fragment.getId());
            return;
        }
        pinnedFragmentDeadlines.put(fragment.getId(), pinnedUntil);
        pinExpirations.offer(new PinnedFragmentRef(fragment.getId(), pinnedUntil));
        trimStalePinEntries(fragment.getId(), pinnedUntil);
    }

    private Map<String, Long> computeNamespaceTokenUsage(Collection<MemoryFragment> fragments) {
        return fragments.stream()
                .filter(fragment -> fragment.getNamespace() != null && !fragment.getNamespace().isBlank())
                .collect(Collectors.groupingBy(
                        MemoryFragment::getNamespace,
                        Collectors.summingLong(MemoryFragment::getTokenCount)));
    }

    private Map<String, RedundancyStats> computeRedundancyStats(List<MemoryFragment> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, RedundancyStats> stats = new HashMap<>();
        for (MemoryFragment fragment : candidates) {
            double maxPenalty = 0.0;
            double minNovelty = Double.POSITIVE_INFINITY;
            boolean hasPeer = false;
            for (MemoryFragment other : candidates) {
                if (other == fragment) {
                    continue;
                }
                hasPeer = true;
                maxPenalty = Math.max(maxPenalty, fragment.redundancyPenaltyAgainst(other));
                minNovelty = Math.min(minNovelty, fragment.noveltyBonusAgainst(other));
            }
            stats.put(fragment.getId(), new RedundancyStats(
                    maxPenalty,
                    hasPeer ? minNovelty : 0.0));
        }
        return stats;
    }

    private void trimStalePinEntries(String fragmentId, long activeDeadline) {
        if (pinExpirations.size() <= pinnedFragmentDeadlines.size() * 4L + 32L) {
            return;
        }
        pinExpirations.removeIf(ref -> ref.fragmentId().equals(fragmentId)
                && ref.pinnedUntilEpochMillis() != activeDeadline);
    }

    private record RedundancyStats(double redundancyPenalty, double noveltyBonus) {}

    private static final class IncrementalRedundancyState {
        private final List<MemoryFragment> fragments = new ArrayList<>();
        private final Map<String, RedundancyStats> stats = new HashMap<>();

        private static IncrementalRedundancyState from(List<MemoryFragment> initial) {
            IncrementalRedundancyState state = new IncrementalRedundancyState();
            for (MemoryFragment fragment : initial) {
                state.add(fragment);
            }
            return state;
        }

        private void add(MemoryFragment candidate) {
            double candidateMaxPenalty = 0.0;
            double candidateMinNovelty = Double.POSITIVE_INFINITY;
            boolean hasPeer = false;
            for (MemoryFragment existing : fragments) {
                hasPeer = true;
                double candidatePenalty = candidate.redundancyPenaltyAgainst(existing);
                double candidateNovelty = candidate.noveltyBonusAgainst(existing);
                candidateMaxPenalty = Math.max(candidateMaxPenalty, candidatePenalty);
                candidateMinNovelty = Math.min(candidateMinNovelty, candidateNovelty);

                RedundancyStats previous = stats.getOrDefault(existing.getId(), new RedundancyStats(0.0, 0.0));
                double updatedPenalty = Math.max(previous.redundancyPenalty(), existing.redundancyPenaltyAgainst(candidate));
                double updatedNovelty = fragments.size() == 1
                        ? existing.noveltyBonusAgainst(candidate)
                        : Math.min(previous.noveltyBonus(), existing.noveltyBonusAgainst(candidate));
                stats.put(existing.getId(), new RedundancyStats(updatedPenalty, updatedNovelty));
            }
            stats.put(candidate.getId(), new RedundancyStats(candidateMaxPenalty, hasPeer ? candidateMinNovelty : 0.0));
            fragments.add(candidate);
        }

        private Map<String, RedundancyStats> snapshot() {
            return Map.copyOf(stats);
        }
    }

    private record PinnedFragmentRef(String fragmentId, long pinnedUntilEpochMillis)
            implements Comparable<PinnedFragmentRef> {
        @Override
        public int compareTo(PinnedFragmentRef other) {
            return Long.compare(this.pinnedUntilEpochMillis, other.pinnedUntilEpochMillis);
        }
    }
}
