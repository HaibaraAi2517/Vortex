package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.SemanticPage;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.l1.CaffeineHotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Coordinates tiered eviction, admission, quota enforcement, tier indexing,
 * and capacity management for the hierarchical memory controller.
 *
 * Extracted from {@link HierarchicalMemoryController} to keep eviction
 * concerns self-contained and testable in isolation.
 */
@Slf4j
@Component
public class TieredEvictionCoordinator {

    private final L1HotStore l1;
    private final SemanticEvictionPolicy evictionPolicy;
    private final NamespaceQuotaManager namespaceQuotaManager;
    private final EvictionDecisionLogger evictionDecisionLogger;
    private final EvictionRegretTracker regretTracker;
    private final MemorySloTracker sloTracker;
    private final FragmentPersistenceManager persistenceManager;
    private final AdaptiveWeightLearner adaptiveWeightLearner;
    private final FragmentPinManager pinManager;

    private final ConcurrentMap<String, NavigableSet<TieredGroupRef>> hotTierIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, NavigableSet<TieredGroupRef>> coldTierIndex = new ConcurrentHashMap<>();
    private final ReentrantLock admissionLock = new ReentrantLock();

    private final double evictionThreshold;
    private final long hotTierRecencyWindowMillis;
    private final int maxColdTierCandidates;
    private final int hotTierExpansionFactor;

    public TieredEvictionCoordinator(
            L1HotStore l1,
            SemanticEvictionPolicy evictionPolicy,
            NamespaceQuotaManager namespaceQuotaManager,
            EvictionDecisionLogger evictionDecisionLogger,
            EvictionRegretTracker regretTracker,
            MemorySloTracker sloTracker,
            FragmentPersistenceManager persistenceManager,
            AdaptiveWeightLearner adaptiveWeightLearner,
            @Lazy FragmentPinManager pinManager,
            @Value("${vortex.kernel.eviction.threshold:0.85}") double evictionThreshold,
            @Value("${vortex.kernel.eviction.hot-tier-window-ms:300000}") long hotTierRecencyWindowMillis,
            @Value("${vortex.kernel.eviction.max-cold-tier-candidates:64}") int maxColdTierCandidates,
            @Value("${vortex.kernel.eviction.hot-tier-expansion-factor:2}") int hotTierExpansionFactor) {
        this.l1 = l1;
        this.evictionPolicy = evictionPolicy;
        this.namespaceQuotaManager = namespaceQuotaManager;
        this.evictionDecisionLogger = evictionDecisionLogger;
        this.regretTracker = regretTracker;
        this.sloTracker = sloTracker;
        this.persistenceManager = persistenceManager;
        this.adaptiveWeightLearner = adaptiveWeightLearner;
        this.pinManager = pinManager;
        this.evictionThreshold = evictionThreshold;
        this.hotTierRecencyWindowMillis = Math.max(1L, hotTierRecencyWindowMillis);
        this.maxColdTierCandidates = Math.max(8, maxColdTierCandidates);
        this.hotTierExpansionFactor = Math.max(1, hotTierExpansionFactor);
    }

    // ---- Scheduled maintenance ----

    @Scheduled(fixedDelayString = "${vortex.kernel.eviction.tier-rebalance-interval-ms:120000}")
    public void rebalanceTierIndexes() {
        rebuildTierIndexes();
    }

    // ---- Public API ----

    /**
     * Proactively evict low-score fragments from L1 when approaching capacity.
     * Called before each store() to keep L1 healthy.
     */
    public void maybeEvict(String namespace, float[] queryEmbedding) {
        pinManager.clearExpiredPins();
        if (namespace == null || namespace.isBlank()) return;
        long current = l1.currentTokenCount();
        long max = l1.maxTokenCapacity();
        if (max == 0 || (double) current / max < evictionThreshold) return;

        List<MemoryFragment> candidates = new ArrayList<>(l1.getAll(namespace));
        if (candidates.isEmpty()) return;

        long targetEvict = Math.max(1L, (long) Math.ceil(max * 0.10));
        List<SemanticEvictionPolicy.EvictionCandidate> toEvict = rankTieredCandidates(
                candidates,
                queryEmbedding,
                targetEvict);
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

    /**
     * Admit a single fragment to L1, enforcing quota and capacity before insertion.
     */
    public boolean admitToL1(MemoryFragment fragment, String context) {
        admissionLock.lock();
        try {
            if (l1.peek(fragment.getId()).isPresent()) {
                l1.put(fragment);
                pinManager.indexPin(fragment);
                reindexTierMembership(fragment);
                return true;
            }
            enforceQuotaBeforeInsert(fragment);
            maybeEvict(fragment.getNamespace(), fragment.getEmbedding());
            if (!ensureCapacityForAdmission(fragment, context)) {
                return false;
            }
            l1.put(fragment);
            pinManager.indexPin(fragment);
            reindexTierMembership(fragment);
            return true;
        } finally {
            admissionLock.unlock();
        }
    }

    /**
     * Admit an entire semantic page to L1 atomically.
     * Used by the paging subsystem when handling page faults.
     */
    public void admitPage(SemanticPage page, List<MemoryFragment> fragments) {
        admitPage(page, fragments, null);
    }

    public void admitPage(SemanticPage page, List<MemoryFragment> fragments, String primaryFragmentId) {
        if (page == null || fragments == null || fragments.isEmpty()) return;
        Set<String> residentAtAdmissionStart = fragments.stream()
                .map(MemoryFragment::getId)
                .filter(fragmentId -> l1.peek(fragmentId).isPresent())
                .collect(Collectors.toSet());
        boolean primaryAdmissionConsumed = primaryFragmentId != null
                && residentAtAdmissionStart.contains(primaryFragmentId);
        admissionLock.lock();
        try {
            for (MemoryFragment fragment : fragments) {
                boolean isPrimary = Objects.equals(primaryFragmentId, fragment.getId());
                if (residentAtAdmissionStart.contains(fragment.getId())) {
                    if (l1.peek(fragment.getId()).isPresent()) {
                        l1.put(fragment);
                        pinManager.indexPin(fragment);
                        reindexTierMembership(fragment);
                    }
                    if (isPrimary) {
                        primaryAdmissionConsumed = true;
                    }
                    continue;
                }
                if (l1.peek(fragment.getId()).isPresent()) {
                    l1.put(fragment);
                    pinManager.indexPin(fragment);
                    reindexTierMembership(fragment);
                    if (isPrimary) {
                        primaryAdmissionConsumed = true;
                    }
                    continue;
                }
                if (isPrimary || (primaryFragmentId == null && !primaryAdmissionConsumed)) {
                    primaryAdmissionConsumed = true;
                    enforceQuotaBeforeInsert(fragment);
                    maybeEvict(fragment.getNamespace(), fragment.getEmbedding());
                    if (ensureCapacityForAdmission(fragment, "page-fault")) {
                        l1.put(fragment);
                        pinManager.indexPin(fragment);
                        reindexTierMembership(fragment);
                    }
                    continue;
                }
                if (canAdmitPageCompanionWithoutReclaim(fragment)) {
                    l1.put(fragment);
                    pinManager.indexPin(fragment);
                    reindexTierMembership(fragment);
                }
            }
        } finally {
            admissionLock.unlock();
        }
    }

    public Map<String, Long> computeNamespaceTokenUsage(Collection<MemoryFragment> fragments) {
        return fragments.stream()
                .filter(fragment -> fragment.getNamespace() != null && !fragment.getNamespace().isBlank())
                .collect(Collectors.groupingBy(
                        MemoryFragment::getNamespace,
                        Collectors.summingLong(MemoryFragment::getTokenCount)));
    }

    // ---- Tier indexing ----

    void reindexTierMembership(MemoryFragment fragment) {
        if (fragment == null || fragment.getNamespace() == null || fragment.getNamespace().isBlank()) {
            return;
        }
        List<MemoryFragment> namespaceFragments = l1.getAll(fragment.getNamespace());
        if (namespaceFragments.isEmpty()) {
            removeFromTierIndexes(fragment);
            return;
        }
        reindexNamespaceTierMembership(namespaceFragments);
    }

    void removeFromTierIndexes(MemoryFragment fragment) {
        if (fragment == null || fragment.getNamespace() == null || fragment.getNamespace().isBlank()) {
            return;
        }
        String groupKey = groupKey(fragment);
        removeTierRef(hotTierIndex.get(fragment.getNamespace()), groupKey);
        removeTierRef(coldTierIndex.get(fragment.getNamespace()), groupKey);
    }

    boolean isHot(MemoryFragment fragment) {
        return System.currentTimeMillis() - fragment.getLastAccessTime() <= hotTierRecencyWindowMillis;
    }

    // ---- Internal: quota and capacity enforcement ----

    private void enforceQuotaBeforeInsert(MemoryFragment incomingFragment) {
        pinManager.clearExpiredPins();
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
        List<SemanticEvictionPolicy.EvictionCandidate> ownCandidates = rankTieredCandidates(
                l1.getAll(incomingFragment.getNamespace()),
                incomingFragment.getEmbedding(),
                requiredTokens);
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
            List<SemanticEvictionPolicy.EvictionCandidate> ranked = rankTieredCandidates(
                    namespaceFragments,
                    incomingFragment.getEmbedding(),
                    Math.min(remainingRequired, borrowedTokens));
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

    private boolean canAdmitPageCompanionWithoutReclaim(MemoryFragment incomingFragment) {
        pinManager.clearExpiredPins();
        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return true;
        }
        long capacity = l1.maxTokenCapacity();
        long requiredTokens = incomingFragment.getTokenCount();
        if (pinManager.getPinnedTokenCount() + requiredTokens > capacity) {
            return false;
        }
        List<MemoryFragment> allFragments = new ArrayList<>(caffeineStore.getAllFragments());
        NamespaceQuotaManager.QuotaSnapshot snapshot = namespaceQuotaManager.snapshot(
                allFragments,
                capacity,
                incomingFragment.getNamespace());
        long projectedUsage = snapshot.focusNamespaceUsage() + requiredTokens;
        if (projectedUsage > snapshot.hardQuotaPerNamespace()) {
            return false;
        }
        return caffeineStore.currentTokenCount() + requiredTokens <= capacity;
    }

    private boolean ensureCapacityForAdmission(MemoryFragment incomingFragment, String context) {
        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return true;
        }
        long capacity = l1.maxTokenCapacity();
        long pinnedTokens = pinManager.getPinnedTokenCount();
        long requiredTokens = incomingFragment.getTokenCount();
        if (pinnedTokens + requiredTokens > capacity) {
            log.warn(
                    "Skipped L1 admission due to insufficient effective capacity fragmentId={} namespace={} context={} pinnedTokens={} requiredTokens={} capacity={}",
                    incomingFragment.getId(),
                    incomingFragment.getNamespace(),
                    context,
                    pinnedTokens,
                    requiredTokens,
                    capacity);
            return false;
        }

        long gap = (caffeineStore.currentTokenCount() + requiredTokens) - capacity;
        if (gap <= 0) {
            return true;
        }

        long released = reclaimAdmissionGap(incomingFragment, gap);
        if (released < gap) {
            log.warn(
                    "Skipped L1 admission after unsuccessful victim search fragmentId={} namespace={} context={} gap={} released={}",
                    incomingFragment.getId(),
                    incomingFragment.getNamespace(),
                    context,
                    gap,
                    released);
            return false;
        }
        return true;
    }

    private long reclaimAdmissionGap(MemoryFragment incomingFragment, long gap) {
        List<SemanticEvictionPolicy.EvictionCandidate> localCandidates = rankTieredCandidates(
                l1.getAll(incomingFragment.getNamespace()),
                incomingFragment.getEmbedding(),
                gap);
        long released = evictCandidatesUntil(
                localCandidates,
                incomingFragment.getNamespace(),
                gap,
                "capacity-self-reclaim");
        if (released >= gap) {
            return released;
        }

        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return released;
        }
        long remaining = gap - released;
        List<MemoryFragment> allFragments = new ArrayList<>(caffeineStore.getAllFragments());
        List<SemanticEvictionPolicy.EvictionCandidate> globalCandidates = rankTieredCandidates(
                allFragments.stream()
                        .filter(fragment -> !Objects.equals(fragment.getNamespace(), incomingFragment.getNamespace()))
                        .toList(),
                incomingFragment.getEmbedding(),
                remaining);
        return released + evictCandidatesUntil(
                globalCandidates,
                incomingFragment.getNamespace(),
                remaining,
                "capacity-global-reclaim");
    }

    // ---- Internal: eviction execution ----

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
            pinManager.removePinIndex(fragment);
            removeFromTierIndexes(fragment);
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
                pinManager.indexPin(fragment);
                reindexTierMembership(fragment);
            }
            return fragment.isPinned() ? List.of() : List.of(fragment);
        }
        return l1.getAll(candidate.fragment().getNamespace()).stream()
                .map(fragment -> {
                    if (fragment.clearExpiredPin()) {
                        l1.put(fragment, false);
                        pinManager.indexPin(fragment);
                        reindexTierMembership(fragment);
                    }
                    return fragment;
                })
                .filter(fragment -> groupId.equals(fragment.getReasoningChainId()))
                .filter(fragment -> !fragment.isPinned())
                .toList();
    }

    // ---- Internal: tiered candidate ranking ----

    List<SemanticEvictionPolicy.EvictionCandidate> rankTieredCandidates(
            Collection<MemoryFragment> candidates,
            float[] queryEmbedding,
            long targetTokens) {
        return rankTieredCandidates(candidates, queryEmbedding, targetTokens, evictionPolicy.defaultProfile());
    }

    List<SemanticEvictionPolicy.EvictionCandidate> rankTieredCandidates(
            Collection<MemoryFragment> candidates,
            float[] queryEmbedding,
            long targetTokens,
            AdaptiveWeightProfile profile) {
        List<MemoryFragment> filtered = candidates.stream()
                .filter(Objects::nonNull)
                .filter(fragment -> !fragment.isPinned())
                .toList();
        if (filtered.isEmpty()) {
            return List.of();
        }
        TieredCandidatePool pool = resolveTieredCandidatePool(filtered);
        List<MemoryFragment> scoped = pool.coldTier();
        if (scoped.isEmpty()) {
            scoped = pool.hotTier();
        }
        List<SemanticEvictionPolicy.EvictionCandidate> ranked = evictionPolicy.rankCandidates(scoped, queryEmbedding, profile);
        ranked = applyRegretAwareOrdering(ranked, targetTokens);
        long coldCoverage = coveredTokens(ranked);
        boolean coldOnly = !pool.coldTier().isEmpty() && (coldCoverage >= targetTokens || pool.hotTier().isEmpty());
        boolean hotOnly = pool.coldTier().isEmpty() && !pool.hotTier().isEmpty();
        if (coldOnly || hotOnly) {
            sloTracker.recordTieredSelection(coldOnly, hotOnly, false);
            return ranked;
        }
        List<MemoryFragment> expanded = new ArrayList<>(pool.coldTier());
        expanded.addAll(limitHotTier(pool.hotTier(), targetTokens - coldCoverage));
        sloTracker.recordTieredSelection(false, false, true);
        return applyRegretAwareOrdering(evictionPolicy.rankCandidates(expanded, queryEmbedding, profile), targetTokens);
    }

    List<String> rankEvictionForEvaluation(
            Collection<MemoryFragment> candidates,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile) {
        return rankTieredCandidates(candidates, queryEmbedding, Long.MAX_VALUE, profile).stream()
                .map(candidate -> candidate.fragment().getId())
                .toList();
    }

    private TieredCandidatePool resolveTieredCandidatePool(List<MemoryFragment> candidates) {
        if (candidates.isEmpty()) {
            return new TieredCandidatePool(List.of(), List.of());
        }
        String namespace = candidates.getFirst().getNamespace();
        if (namespace == null || namespace.isBlank()) {
            return buildTieredCandidatePool(candidates);
        }
        Map<String, MemoryFragment> candidateMap = candidates.stream()
                .collect(Collectors.toMap(MemoryFragment::getId, fragment -> fragment, (left, right) -> left));
        Map<String, List<MemoryFragment>> candidateGroups = groupFragments(candidates);
        List<MemoryFragment> coldTier = collectTierMembers(
                coldTierIndex.get(namespace),
                candidateMap,
                candidateGroups,
                maxColdTierCandidates);
        List<MemoryFragment> hotTier = collectTierMembers(
                hotTierIndex.get(namespace),
                candidateMap,
                candidateGroups,
                Integer.MAX_VALUE);
        if (coldTier.isEmpty() && hotTier.isEmpty()) {
            return buildTieredCandidatePool(candidates);
        }
        return new TieredCandidatePool(List.copyOf(coldTier), List.copyOf(hotTier));
    }

    private TieredCandidatePool buildTieredCandidatePool(List<MemoryFragment> candidates) {
        List<TieredGroupRef> hotGroups = new ArrayList<>();
        List<TieredGroupRef> coldGroups = new ArrayList<>();
        Map<String, List<MemoryFragment>> groups = groupFragments(candidates);
        for (List<MemoryFragment> group : groups.values()) {
            TieredGroupRef ref = TieredGroupRef.of(group, hotTierRecencyWindowMillis);
            if (ref == null) {
                continue;
            }
            if (ref.hot()) {
                hotGroups.add(ref);
            } else {
                coldGroups.add(ref);
            }
        }
        Collections.sort(coldGroups);
        Collections.sort(hotGroups);
        return new TieredCandidatePool(
                flattenTierGroups(coldGroups, groups, maxColdTierCandidates),
                flattenTierGroups(hotGroups, groups, Integer.MAX_VALUE));
    }

    private List<MemoryFragment> collectTierMembers(
            NavigableSet<TieredGroupRef> index,
            Map<String, MemoryFragment> candidateMap,
            Map<String, List<MemoryFragment>> candidateGroups,
            int limit) {
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        List<MemoryFragment> selected = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        int selectedGroups = 0;
        for (TieredGroupRef ref : index) {
            List<MemoryFragment> group = candidateGroups.get(ref.groupKey());
            if (group == null || group.isEmpty()) {
                continue;
            }
            List<MemoryFragment> activeMembers = group.stream()
                    .map(fragment -> candidateMap.get(fragment.getId()))
                    .filter(Objects::nonNull)
                    .filter(fragment -> !fragment.isPinned())
                    .sorted(Comparator
                            .comparingLong(MemoryFragment::getLastAccessTime)
                            .thenComparingDouble(MemoryFragment::getImportance)
                            .thenComparing(MemoryFragment::getId))
                    .toList();
            if (activeMembers.isEmpty()) {
                continue;
            }
            for (MemoryFragment fragment : activeMembers) {
                if (selectedIds.add(fragment.getId())) {
                    selected.add(fragment);
                }
            }
            selectedGroups++;
            if (selectedGroups >= limit) {
                break;
            }
        }
        return selected;
    }

    private void rebuildTierIndexes() {
        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return;
        }
        Map<String, List<MemoryFragment>> fragmentsByNamespace = caffeineStore.getAllFragments().stream()
                .filter(fragment -> fragment.getNamespace() != null && !fragment.getNamespace().isBlank())
                .collect(Collectors.groupingBy(MemoryFragment::getNamespace));
        hotTierIndex.clear();
        coldTierIndex.clear();
        fragmentsByNamespace.values().forEach(this::reindexNamespaceTierMembership);
    }

    private void reindexNamespaceTierMembership(List<MemoryFragment> namespaceFragments) {
        if (namespaceFragments == null || namespaceFragments.isEmpty()) {
            return;
        }
        String namespace = namespaceFragments.getFirst().getNamespace();
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        NavigableSet<TieredGroupRef> hotGroups = new TreeSet<>();
        NavigableSet<TieredGroupRef> coldGroups = new TreeSet<>();
        for (List<MemoryFragment> group : groupFragments(namespaceFragments).values()) {
            TieredGroupRef ref = TieredGroupRef.of(group, hotTierRecencyWindowMillis);
            if (ref == null) {
                continue;
            }
            if (ref.hot()) {
                hotGroups.add(ref);
            } else {
                coldGroups.add(ref);
            }
        }
        if (hotGroups.isEmpty()) {
            hotTierIndex.remove(namespace);
        } else {
            hotTierIndex.put(namespace, hotGroups);
        }
        if (coldGroups.isEmpty()) {
            coldTierIndex.remove(namespace);
        } else {
            coldTierIndex.put(namespace, coldGroups);
        }
    }

    private void removeTierRef(NavigableSet<TieredGroupRef> index, String groupKey) {
        if (index == null || groupKey == null) {
            return;
        }
        index.removeIf(ref -> groupKey.equals(ref.groupKey()));
    }

    private List<MemoryFragment> limitHotTier(List<MemoryFragment> hotTier, long remainingTokens) {
        int maxGroups = Math.max(1, maxColdTierCandidates * hotTierExpansionFactor);
        Map<String, List<MemoryFragment>> groups = hotTier.stream()
                .collect(Collectors.groupingBy(this::groupKey, LinkedHashMap::new, Collectors.toList()));
        List<MemoryFragment> selected = new ArrayList<>();
        long covered = 0L;
        int selectedGroups = 0;
        for (List<MemoryFragment> group : groups.values()) {
            if (selectedGroups >= maxGroups) {
                break;
            }
            selected.addAll(group);
            covered += group.stream().mapToLong(MemoryFragment::getTokenCount).sum();
            selectedGroups++;
            if (covered >= remainingTokens && selectedGroups >= Math.min(groups.size(), hotTierExpansionFactor)) {
                break;
            }
        }
        return selected;
    }

    private List<MemoryFragment> flattenTierGroups(
            List<TieredGroupRef> groupOrder,
            Map<String, List<MemoryFragment>> groups,
            int maxGroups) {
        List<MemoryFragment> selected = new ArrayList<>();
        int selectedGroups = 0;
        for (TieredGroupRef ref : groupOrder) {
            List<MemoryFragment> members = groups.get(ref.groupKey());
            if (members == null || members.isEmpty()) {
                continue;
            }
            selected.addAll(members.stream()
                    .filter(fragment -> !fragment.isPinned())
                    .sorted(Comparator
                            .comparingLong(MemoryFragment::getLastAccessTime)
                            .thenComparingDouble(MemoryFragment::getImportance)
                            .thenComparing(MemoryFragment::getId))
                    .toList());
            selectedGroups++;
            if (selectedGroups >= maxGroups) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private long coveredTokens(List<SemanticEvictionPolicy.EvictionCandidate> ranked) {
        long covered = 0L;
        Set<String> groups = new HashSet<>();
        for (SemanticEvictionPolicy.EvictionCandidate candidate : ranked) {
            String groupKey = groupKey(candidate.fragment());
            if (groups.add(groupKey)) {
                covered += candidate.groupTokenCount();
            }
        }
        return covered;
    }

    private List<SemanticEvictionPolicy.EvictionCandidate> applyRegretAwareOrdering(
            List<SemanticEvictionPolicy.EvictionCandidate> ranked,
            long targetTokens) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        List<SemanticEvictionPolicy.EvictionCandidate> unprotected = new ArrayList<>();
        List<SemanticEvictionPolicy.EvictionCandidate> protectedCandidates = new ArrayList<>();
        for (SemanticEvictionPolicy.EvictionCandidate candidate : ranked) {
            if (regretTracker.protectionScore(candidate.fragment()) > 0.0) {
                protectedCandidates.add(candidate);
            } else {
                unprotected.add(candidate);
            }
        }
        if (protectedCandidates.isEmpty() || unprotected.isEmpty()) {
            return ranked;
        }
        List<SemanticEvictionPolicy.EvictionCandidate> reordered = new ArrayList<>(ranked.size());
        reordered.addAll(unprotected);
        reordered.addAll(protectedCandidates);
        return targetTokens > 0 && coveredTokens(unprotected) >= targetTokens ? reordered : reordered;
    }

    // ---- Internal: grouping helpers ----

    private Map<String, List<MemoryFragment>> groupFragments(Collection<MemoryFragment> fragments) {
        return fragments.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(this::groupKey));
    }

    private String groupKey(MemoryFragment fragment) {
        return groupKeyOf(fragment);
    }

    /**
     * Extract the group key for a fragment.
     * Uses the reasoning chain ID if present; otherwise falls back to a
     * per-fragment singleton key.
     */
    public static String groupKeyOf(MemoryFragment fragment) {
        String reasoningChainId = fragment.getReasoningChainId();
        if (reasoningChainId == null || reasoningChainId.isBlank()) {
            return "__self__:" + fragment.getId();
        }
        return reasoningChainId;
    }

    // ---- Public inner types ----

    public record ScoredCandidate(MemoryFragment fragment, double score) {}

    public record TieredCandidatePool(List<MemoryFragment> coldTier, List<MemoryFragment> hotTier) {}

    public record TieredGroupRef(
            String groupKey,
            long lastAccessTime,
            double importance,
            boolean hot)
            implements Comparable<TieredGroupRef> {
        public static TieredGroupRef of(List<MemoryFragment> group, long hotTierRecencyWindowMillis) {
            if (group == null || group.isEmpty()) {
                return null;
            }
            List<MemoryFragment> activeMembers = group.stream()
                    .filter(fragment -> !fragment.isPinned())
                    .toList();
            if (activeMembers.isEmpty()) {
                return null;
            }
            long mostRecentAccess = activeMembers.stream()
                    .mapToLong(MemoryFragment::getLastAccessTime)
                    .max()
                    .orElse(0L);
            double averageImportance = activeMembers.stream()
                    .mapToDouble(MemoryFragment::getImportance)
                    .average()
                    .orElse(0.0);
            MemoryFragment representative = activeMembers.stream()
                    .max(Comparator.comparingLong(MemoryFragment::getLastAccessTime)
                            .thenComparingDouble(MemoryFragment::getImportance)
                            .thenComparing(MemoryFragment::getId))
                    .orElse(activeMembers.getFirst());
            return new TieredGroupRef(
                    groupKeyOf(representative),
                    mostRecentAccess,
                    averageImportance,
                    System.currentTimeMillis() - mostRecentAccess <= hotTierRecencyWindowMillis);
        }

        @Override
        public int compareTo(TieredGroupRef other) {
            int byAccess = Long.compare(this.lastAccessTime, other.lastAccessTime);
            if (byAccess != 0) {
                return byAccess;
            }
            int byImportance = Double.compare(this.importance, other.importance);
            if (byImportance != 0) {
                return byImportance;
            }
            return this.groupKey.compareTo(other.groupKey);
        }
    }
}
