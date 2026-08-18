package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.SemanticPage;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L1HotStoreAdmin;
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

    private static final int MAX_OPTIMISTIC_ADMISSION_ATTEMPTS = 2;
    private static final int ADMISSION_PLANNING_GATE_STRIPES = 64;
    private static final Set<String> SCOPED_LOCAL_RECLAIM_REASONS =
            Set.of("quota-self-reclaim", "capacity-self-reclaim", "semantic");

    private final L1HotStore l1;
    private final L1HotStoreAdmin l1Admin;
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
    private final Map<String, TierGroupKey> tierGroupByFragment = new HashMap<>();
    private final Map<TierGroupKey, LinkedHashSet<String>> tierGroupMembers = new HashMap<>();
    private final Map<TierGroupKey, TieredGroupRef> tierRefByGroup = new HashMap<>();
    private final ReentrantLock[] admissionPlanningGates = createAdmissionPlanningGates();
    private final ReentrantLock admissionLock = new ReentrantLock();
    private long admissionEpoch;

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
        this.l1Admin = l1 instanceof L1HotStoreAdmin admin ? admin : null;
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
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            rebuildTierIndexes();
            markAdmissionStateChanged();
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
        }
    }

    // ---- Public API ----

    /**
     * Proactively evict low-score fragments from L1 when approaching capacity.
     * Called before each store() to keep L1 healthy.
     */
    public void maybeEvict(String namespace, float[] queryEmbedding) {
        List<PendingPersistence> pendingPersistence = new ArrayList<>();
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            maybeEvictLocked(namespace, queryEmbedding, pendingPersistence);
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
            persistAfterUnlock(pendingPersistence);
        }
    }

    private void maybeEvictLocked(
            String namespace,
            float[] queryEmbedding,
            List<PendingPersistence> pendingPersistence) {
        queueClearedPinsForPersistence(pendingPersistence);
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
                    null,
                    namespaceTokenUsage,
                    pendingPersistence);
            if (evictedTokens >= targetEvict) break;
        }
        log.debug("Proactive eviction: namespace={} removed {} fragments ({} tokens)",
                namespace, toEvict.size(), evictedTokens);
    }

    /**
     * Admit a single fragment to L1, enforcing quota and capacity before insertion.
     */
    public boolean admitToL1(MemoryFragment fragment, String context) {
        sloTracker.recordAdmissionRequest();
        List<PendingPersistence> pendingPersistence = new ArrayList<>();
        try {
            if (l1Admin != null) {
                sloTracker.recordAdmissionDirectAttempt();
                DirectAdmissionResult directResult =
                        tryDirectAdmission(fragment, pendingPersistence);
                if (directResult == DirectAdmissionResult.ADMITTED) {
                    sloTracker.recordAdmissionDirectCommit();
                    return true;
                }
                if (directResult == DirectAdmissionResult.REJECTED) {
                    sloTracker.recordAdmissionDirectRejection();
                    logRejectedAdmission(fragment, context);
                    return false;
                }
                sloTracker.recordAdmissionDirectEscalation();

                ReentrantLock planningGate = planningGateFor(fragment.getNamespace());
                boolean waitedForPlanningGate = acquirePlanningGate(planningGate);
                try {
                    if (waitedForPlanningGate) {
                        DirectAdmissionResult gatedDirectResult =
                                tryDirectAdmission(fragment, pendingPersistence);
                        if (gatedDirectResult == DirectAdmissionResult.ADMITTED) {
                            sloTracker.recordAdmissionDirectCommit();
                            return true;
                        }
                        if (gatedDirectResult == DirectAdmissionResult.REJECTED) {
                            sloTracker.recordAdmissionDirectRejection();
                            logRejectedAdmission(fragment, context);
                            return false;
                        }
                    }

                    for (int attempt = 0; attempt < MAX_OPTIMISTIC_ADMISSION_ATTEMPTS; attempt++) {
                        sloTracker.recordAdmissionOptimisticAttempt();
                        AdmissionSnapshot snapshot =
                                captureAdmissionSnapshot(fragment, pendingPersistence, false);
                        long planningStartedNanos = System.nanoTime();
                        AdmissionPlan plan = planAdmission(snapshot);
                        long planningNanos = System.nanoTime() - planningStartedNanos;
                        if (plan.detailedSnapshotRequired()) {
                            snapshot = captureAdmissionSnapshot(fragment, pendingPersistence, true);
                            planningStartedNanos = System.nanoTime();
                            plan = planAdmission(snapshot);
                            planningNanos += System.nanoTime() - planningStartedNanos;
                        }
                        sloTracker.recordAdmissionPlanning(planningNanos);

                        AdmissionCommitResult commitResult =
                                tryCommitAdmission(plan, fragment, context, pendingPersistence);
                        if (commitResult != AdmissionCommitResult.CONFLICT) {
                            sloTracker.recordAdmissionOptimisticCommit();
                            recordTieredSelections(plan.tieredSelections());
                            return commitResult == AdmissionCommitResult.ADMITTED;
                        }
                        sloTracker.recordAdmissionOptimisticConflict();
                    }
                    sloTracker.recordAdmissionFallback();

                    long lockAcquiredNanos = acquireAdmissionLock();
                    try {
                        return admitToL1Locked(fragment, context, pendingPersistence);
                    } finally {
                        releaseAdmissionLock(lockAcquiredNanos);
                    }
                } finally {
                    planningGate.unlock();
                }
            }

            long lockAcquiredNanos = acquireAdmissionLock();
            try {
                return admitToL1Locked(fragment, context, pendingPersistence);
            } finally {
                releaseAdmissionLock(lockAcquiredNanos);
            }
        } finally {
            persistAfterUnlock(pendingPersistence);
        }
    }

    private void logRejectedAdmission(MemoryFragment fragment, String context) {
        log.warn(
                "Skipped L1 admission due to insufficient effective capacity fragmentId={} namespace={} context={} pinnedTokens={} requiredTokens={} capacity={}",
                fragment.getId(),
                fragment.getNamespace(),
                context,
                pinManager.getPinnedTokenCount(),
                fragment.getTokenCount(),
                l1.maxTokenCapacity());
    }

    private DirectAdmissionResult tryDirectAdmission(
            MemoryFragment fragment,
            List<PendingPersistence> pendingPersistence) {
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            queueClearedPinsForPersistence(pendingPersistence);
            MemoryFragment existing = l1.peek(fragment.getId()).orElse(null);
            NoReclaimAdmissionDecision decision =
                    evaluateNoReclaimAdmissionLocked(fragment, existing);
            if (decision == NoReclaimAdmissionDecision.REJECT) {
                return DirectAdmissionResult.REJECTED;
            }
            if (decision == NoReclaimAdmissionDecision.PLAN_REQUIRED) {
                return DirectAdmissionResult.ESCALATED;
            }
            replaceResidentFragment(existing, fragment, true);
            return DirectAdmissionResult.ADMITTED;
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
        }
    }

    private boolean admitToL1Locked(
            MemoryFragment fragment,
            String context,
            List<PendingPersistence> pendingPersistence) {
        MemoryFragment existing = l1.peek(fragment.getId()).orElse(null);
        if (allocationChanged(existing, fragment)) {
            enforceQuotaBeforeWrite(fragment, existing, pendingPersistence);
        }
        if (existing == null) {
            maybeEvictLocked(fragment.getNamespace(), fragment.getEmbedding(), pendingPersistence);
        }
        if (!ensureCapacityForAdmission(fragment, existing, context, pendingPersistence)) {
            return false;
        }
        replaceResidentFragment(existing, fragment, true);
        return true;
    }

    private AdmissionSnapshot captureAdmissionSnapshot(
            MemoryFragment incomingFragment,
            List<PendingPersistence> pendingPersistence,
            boolean includeResidents) {
        long epoch;
        Collection<MemoryFragment> residentReferences;
        MemoryFragment frozenIncoming;
        MemoryFragment frozenExisting;
        long currentTokens;
        long capacity;
        long pinnedTokens;
        long existingPinnedTokens;
        QuotaProjectionState quotaState;
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            queueClearedPinsForPersistence(pendingPersistence);
            MemoryFragment existing = l1.peek(incomingFragment.getId()).orElse(null);
            residentReferences = includeResidents
                    ? l1Admin.allFragments()
                    : List.of();
            epoch = admissionEpoch;
            frozenIncoming = freezeFragment(incomingFragment);
            frozenExisting = freezeFragment(existing);
            currentTokens = l1.currentTokenCount();
            capacity = l1.maxTokenCapacity();
            pinnedTokens = pinManager.getPinnedTokenCount();
            existingPinnedTokens =
                    existing == null ? 0L : pinManager.getIndexedPinnedTokenCount(existing.getId());
            quotaState =
                    quotaProjectionStateExcludingExisting(incomingFragment.getNamespace(), existing);
        } finally {
            releaseAdmissionLock(
                    lockAcquiredNanos,
                    includeResidents
                            ? AdmissionLockPhase.DETAILED_SNAPSHOT
                            : AdmissionLockPhase.SUMMARY_SNAPSHOT);
        }
        List<MemoryFragment> residents = List.of();
        if (includeResidents) {
            long freezeStartedNanos = System.nanoTime();
            try {
                residents = residentReferences.stream().map(this::freezeFragment).toList();
            } finally {
                sloTracker.recordAdmissionDetailedSnapshotFreeze(
                        System.nanoTime() - freezeStartedNanos);
            }
        }
        return new AdmissionSnapshot(
                epoch,
                residents,
                includeResidents,
                frozenIncoming,
                frozenExisting,
                currentTokens,
                capacity,
                pinnedTokens,
                existingPinnedTokens,
                quotaState.focusNamespaceUsage(),
                quotaState.activeNamespaceCount());
    }

    private AdmissionPlan planAdmission(AdmissionSnapshot snapshot) {
        if (!snapshot.residentsCaptured()) {
            return planAdmissionFromSummary(snapshot);
        }
        AdmissionPlanningState state = new AdmissionPlanningState(snapshot.residents());
        MemoryFragment incoming = snapshot.incoming();
        MemoryFragment existing = snapshot.existing();

        if (allocationChanged(existing, incoming)) {
            planQuotaBeforeWrite(state, incoming, existing, snapshot.capacity());
        }
        if (existing == null) {
            planProactiveEviction(state, incoming, snapshot.capacity());
        }
        boolean admitted = planCapacityForAdmission(state, snapshot, incoming, existing);
        return new AdmissionPlan(
                snapshot,
                admitted,
                admitted && state.fastCommitEligible && state.plannedEvictions.isEmpty(),
                false,
                List.copyOf(state.plannedEvictions),
                List.copyOf(state.tieredSelections));
    }

    private AdmissionPlan planAdmissionFromSummary(AdmissionSnapshot snapshot) {
        MemoryFragment incoming = snapshot.incoming();
        MemoryFragment existing = snapshot.existing();
        long requiredTokens = incoming.getTokenCount();
        long existingTokens = existing == null ? 0L : existing.getTokenCount();

        if (allocationChanged(existing, incoming)) {
            long hardQuota = namespaceQuotaManager.hardQuotaPerNamespace(
                    snapshot.capacity(),
                    snapshot.activeNamespaceCount());
            if (snapshot.focusNamespaceUsage() + requiredTokens > hardQuota) {
                return detailedSnapshotPlan(snapshot);
            }
        }
        if (existing == null
                && incoming.getNamespace() != null
                && !incoming.getNamespace().isBlank()
                && snapshot.capacity() != 0
                && (double) snapshot.currentTokens() / snapshot.capacity() >= evictionThreshold) {
            return detailedSnapshotPlan(snapshot);
        }

        long effectiveProtectedTokens =
                snapshot.pinnedTokens() - snapshot.existingPinnedTokens() + requiredTokens;
        if (effectiveProtectedTokens > snapshot.capacity()) {
            return new AdmissionPlan(
                    snapshot,
                    false,
                    false,
                    false,
                    List.of(),
                    List.of());
        }
        long projectedTokens =
                snapshot.currentTokens() - existingTokens + requiredTokens;
        if (projectedTokens > snapshot.capacity()) {
            return detailedSnapshotPlan(snapshot);
        }
        return new AdmissionPlan(
                snapshot,
                true,
                true,
                false,
                List.of(),
                List.of());
    }

    private AdmissionPlan detailedSnapshotPlan(AdmissionSnapshot snapshot) {
        return new AdmissionPlan(
                snapshot,
                false,
                false,
                true,
                List.of(),
                List.of());
    }

    private void planQuotaBeforeWrite(
            AdmissionPlanningState state,
            MemoryFragment incomingFragment,
            MemoryFragment existingFragment,
            long capacity) {
        List<MemoryFragment> allFragments = state.residents().stream()
                .filter(fragment -> existingFragment == null
                        || !Objects.equals(fragment.getId(), existingFragment.getId()))
                .toList();
        NamespaceQuotaManager.QuotaSnapshot snapshot = namespaceQuotaManager.snapshot(
                allFragments,
                capacity,
                incomingFragment.getNamespace());
        long projectedUsage = snapshot.focusNamespaceUsage() + incomingFragment.getTokenCount();
        if (projectedUsage <= snapshot.hardQuotaPerNamespace()) {
            return;
        }
        state.requireStrictCommit();

        long requiredTokens = projectedUsage - snapshot.hardQuotaPerNamespace();
        List<SemanticEvictionPolicy.EvictionCandidate> ownCandidates = rankPlanningCandidates(
                state,
                allFragments.stream()
                        .filter(fragment -> Objects.equals(fragment.getNamespace(), incomingFragment.getNamespace()))
                        .toList(),
                incomingFragment.getEmbedding(),
                requiredTokens);
        long released = planEvictCandidatesUntil(
                state,
                ownCandidates,
                incomingFragment.getNamespace(),
                requiredTokens,
                "quota-self-reclaim",
                0L,
                existingFragment == null ? null : existingFragment.getId());
        if (released >= requiredTokens) {
            return;
        }

        long remainingRequired = requiredTokens - released;
        for (String otherNamespace : namespaceQuotaManager.evictionPriorityNamespaces(
                allFragments, capacity, incomingFragment.getNamespace())) {
            List<MemoryFragment> namespaceFragments = allFragments.stream()
                    .filter(fragment -> Objects.equals(fragment.getNamespace(), otherNamespace))
                    .toList();
            NamespaceQuotaManager.QuotaSnapshot currentSnapshot = namespaceQuotaManager.snapshot(
                    allFragments,
                    capacity,
                    otherNamespace);
            long borrowedTokens = Math.max(0L,
                    namespaceFragments.stream().mapToLong(MemoryFragment::getTokenCount).sum()
                            - currentSnapshot.hardQuotaPerNamespace());
            if (borrowedTokens <= 0) {
                continue;
            }
            long reclaimTarget = Math.min(remainingRequired, borrowedTokens);
            List<SemanticEvictionPolicy.EvictionCandidate> ranked = rankPlanningCandidates(
                    state,
                    namespaceFragments,
                    incomingFragment.getEmbedding(),
                    reclaimTarget);
            long evicted = planEvictCandidatesUntil(
                    state,
                    ranked,
                    otherNamespace,
                    reclaimTarget,
                    "quota-borrow-reclaim",
                    currentSnapshot.hardQuotaPerNamespace(),
                    existingFragment == null ? null : existingFragment.getId());
            remainingRequired -= evicted;
            if (remainingRequired <= 0) {
                break;
            }
        }
    }

    private void planProactiveEviction(
            AdmissionPlanningState state,
            MemoryFragment incomingFragment,
            long capacity) {
        if (incomingFragment.getNamespace() == null || incomingFragment.getNamespace().isBlank()) {
            return;
        }
        long currentTokens = state.currentTokenCount();
        if (capacity == 0 || (double) currentTokens / capacity < evictionThreshold) {
            return;
        }
        state.requireStrictCommit();
        List<MemoryFragment> candidates = state.namespaceResidents(incomingFragment.getNamespace());
        if (candidates.isEmpty()) {
            return;
        }
        long targetEvict = Math.max(1L, (long) Math.ceil(capacity * 0.10));
        List<SemanticEvictionPolicy.EvictionCandidate> ranked = rankPlanningCandidates(
                state,
                candidates,
                incomingFragment.getEmbedding(),
                targetEvict);
        planEvictCandidatesUntil(
                state,
                ranked,
                incomingFragment.getNamespace(),
                targetEvict,
                "semantic",
                0L,
                null);
    }

    private boolean planCapacityForAdmission(
            AdmissionPlanningState state,
            AdmissionSnapshot snapshot,
            MemoryFragment incomingFragment,
            MemoryFragment existingFragment) {
        long requiredTokens = incomingFragment.getTokenCount();
        long existingTokens = existingFragment == null ? 0L : existingFragment.getTokenCount();
        long effectiveProtectedTokens =
                snapshot.pinnedTokens() - snapshot.existingPinnedTokens() + requiredTokens;
        if (effectiveProtectedTokens > snapshot.capacity()) {
            state.requireStrictCommit();
            return false;
        }

        long gap = (state.currentTokenCount() - existingTokens + requiredTokens) - snapshot.capacity();
        if (gap <= 0) {
            return true;
        }
        state.requireStrictCommit();

        String excludedFragmentId = existingFragment == null ? null : existingFragment.getId();
        List<SemanticEvictionPolicy.EvictionCandidate> localCandidates = rankPlanningCandidates(
                state,
                state.namespaceResidents(incomingFragment.getNamespace()).stream()
                        .filter(fragment -> !Objects.equals(fragment.getId(), excludedFragmentId))
                        .toList(),
                incomingFragment.getEmbedding(),
                gap);
        long released = planEvictCandidatesUntil(
                state,
                localCandidates,
                incomingFragment.getNamespace(),
                gap,
                "capacity-self-reclaim",
                0L,
                excludedFragmentId);
        if (released >= gap) {
            return true;
        }

        long remaining = gap - released;
        List<SemanticEvictionPolicy.EvictionCandidate> globalCandidates = rankPlanningCandidates(
                state,
                state.residents().stream()
                        .filter(fragment -> !Objects.equals(fragment.getId(), excludedFragmentId))
                        .filter(fragment -> !Objects.equals(
                                fragment.getNamespace(), incomingFragment.getNamespace()))
                        .toList(),
                incomingFragment.getEmbedding(),
                remaining);
        released += planEvictCandidatesUntil(
                state,
                globalCandidates,
                incomingFragment.getNamespace(),
                remaining,
                "capacity-global-reclaim",
                0L,
                excludedFragmentId);
        return released >= gap;
    }

    private List<SemanticEvictionPolicy.EvictionCandidate> rankPlanningCandidates(
            AdmissionPlanningState state,
            Collection<MemoryFragment> candidates,
            float[] queryEmbedding,
            long targetTokens) {
        RankedTieredCandidates ranked = rankTieredCandidatesDetailed(
                candidates,
                queryEmbedding,
                targetTokens,
                evictionPolicy.defaultProfile(),
                false);
        if (ranked.selection() != null) {
            state.tieredSelections.add(ranked.selection());
        }
        return ranked.candidates();
    }

    private long planEvictCandidatesUntil(
            AdmissionPlanningState state,
            List<SemanticEvictionPolicy.EvictionCandidate> candidates,
            String triggerNamespace,
            long targetTokens,
            String reason,
            long minRemainingTokens,
            String excludedFragmentId) {
        Set<String> evictedGroups = new HashSet<>();
        long evictedTokens = 0L;
        Map<String, Long> namespaceTokenUsage = computeNamespaceTokenUsage(
                candidates.stream().map(SemanticEvictionPolicy.EvictionCandidate::fragment).toList());
        for (SemanticEvictionPolicy.EvictionCandidate candidate : candidates) {
            if (candidate.pinned()) {
                continue;
            }
            String groupId = candidate.reasoningChainId();
            if (groupId != null && !groupId.isBlank() && !evictedGroups.add(groupId)) {
                continue;
            }
            List<MemoryFragment> evictionGroup =
                    resolvePlanningEvictionGroup(state, candidate, excludedFragmentId);
            if (evictionGroup.isEmpty()) {
                continue;
            }
            String namespace = candidate.fragment().getNamespace();
            long currentNamespaceTokens = namespaceTokenUsage.getOrDefault(namespace, 0L);
            long groupTokens = evictionGroup.stream().mapToLong(MemoryFragment::getTokenCount).sum();
            if (currentNamespaceTokens - groupTokens < minRemainingTokens) {
                continue;
            }
            state.plannedEvictions.add(new PlannedEviction(
                    evictionGroup.stream().map(ResidentState::of).toList(),
                    reason,
                    triggerNamespace,
                    targetTokens,
                    cloneArray(candidate.fragment().getEmbedding())));
            evictionGroup.forEach(fragment -> state.remove(fragment.getId()));
            namespaceTokenUsage.put(namespace, Math.max(0L, currentNamespaceTokens - groupTokens));
            evictedTokens += groupTokens;
            if (evictedTokens >= targetTokens) {
                break;
            }
        }
        return evictedTokens;
    }

    private List<MemoryFragment> resolvePlanningEvictionGroup(
            AdmissionPlanningState state,
            SemanticEvictionPolicy.EvictionCandidate candidate,
            String excludedFragmentId) {
        String groupId = candidate.reasoningChainId();
        if (groupId == null || groupId.isBlank()) {
            MemoryFragment resident = state.resident(candidate.fragment().getId());
            return resident == null
                    || resident.isPinned()
                    || Objects.equals(resident.getId(), excludedFragmentId)
                    ? List.of()
                    : List.of(resident);
        }
        List<MemoryFragment> group = state.namespaceResidents(candidate.fragment().getNamespace()).stream()
                .filter(fragment -> groupId.equals(fragment.getReasoningChainId()))
                .toList();
        if (group.stream().anyMatch(fragment -> Objects.equals(fragment.getId(), excludedFragmentId))) {
            return List.of();
        }
        return group.stream()
                .filter(fragment -> !fragment.isPinned())
                .toList();
    }

    private AdmissionCommitResult tryCommitAdmission(
            AdmissionPlan plan,
            MemoryFragment incomingFragment,
            String context,
            List<PendingPersistence> pendingPersistence) {
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            if (!canCommitAdmission(plan, incomingFragment)) {
                return AdmissionCommitResult.CONFLICT;
            }
            for (PlannedEviction plannedEviction : plan.plannedEvictions()) {
                applyPlannedEviction(plannedEviction, pendingPersistence);
            }
            if (!plan.admitted()) {
                log.warn(
                        "Skipped L1 admission after optimistic planning fragmentId={} namespace={} context={} plannedEvictions={}",
                        incomingFragment.getId(),
                        incomingFragment.getNamespace(),
                        context,
                        plan.plannedEvictions().size());
                return AdmissionCommitResult.REJECTED;
            }
            MemoryFragment existing = l1.peek(incomingFragment.getId()).orElse(null);
            replaceResidentFragment(existing, incomingFragment, true);
            return AdmissionCommitResult.ADMITTED;
        } finally {
            releaseAdmissionLock(lockAcquiredNanos, AdmissionLockPhase.OPTIMISTIC_COMMIT);
        }
    }

    private boolean canCommitAdmission(AdmissionPlan plan, MemoryFragment incomingFragment) {
        AdmissionSnapshot snapshot = plan.snapshot();
        if (!ResidentState.of(snapshot.incoming()).matches(incomingFragment)) {
            return false;
        }
        MemoryFragment currentExisting = l1.peek(incomingFragment.getId()).orElse(null);
        if (!ResidentState.matchesNullable(ResidentState.of(snapshot.existing()), currentExisting)) {
            return false;
        }
        if (plan.fastCommitEligible()) {
            return canCommitWithoutReclaimLocked(incomingFragment, currentExisting);
        }
        if (admissionEpoch == snapshot.epoch()
                && l1.currentTokenCount() == snapshot.currentTokens()
                && pinManager.getPinnedTokenCount() == snapshot.pinnedTokens()) {
            return plannedVictimsStillValid(plan);
        }
        // Global state changed while planning. Local self-reclaim can still commit
        // when its exact victims and every shared invariant remain valid.
        return canCommitScopedLocalReclaim(plan, incomingFragment, currentExisting);
    }

    private boolean plannedVictimsStillValid(AdmissionPlan plan) {
        for (PlannedEviction plannedEviction : plan.plannedEvictions()) {
            for (ResidentState victim : plannedEviction.victims()) {
                MemoryFragment resident = l1.peek(victim.id()).orElse(null);
                if (resident == null || resident.isPinned() || !victim.matches(resident)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean canCommitScopedLocalReclaim(
            AdmissionPlan plan,
            MemoryFragment incomingFragment,
            MemoryFragment currentExisting) {
        if (!isScopedLocalReclaimPlan(plan, incomingFragment, currentExisting)) {
            return false;
        }

        String namespace = incomingFragment.getNamespace();
        long capacity = l1.maxTokenCapacity();
        long simulatedTokens = l1.currentTokenCount();
        long victimTokens = 0L;
        Set<String> victimIds = new HashSet<>();
        Map<TierGroupKey, Set<String>> expectedChainMembers = new HashMap<>();
        try {
            for (PlannedEviction plannedEviction : plan.plannedEvictions()) {
                if ("semantic".equals(plannedEviction.reason())
                        && (capacity == 0L
                                || (double) simulatedTokens / capacity < evictionThreshold)) {
                    return false;
                }
                for (ResidentState victim : plannedEviction.victims()) {
                    if (!victimIds.add(victim.id())) {
                        return false;
                    }
                    MemoryFragment resident = l1.peek(victim.id()).orElse(null);
                    if (resident == null || resident.isPinned() || !victim.matches(resident)) {
                        return false;
                    }
                    simulatedTokens = Math.subtractExact(simulatedTokens, victim.tokenCount());
                    victimTokens = Math.addExact(victimTokens, victim.tokenCount());
                    if (victim.reasoningChainId() != null
                            && !victim.reasoningChainId().isBlank()) {
                        expectedChainMembers
                                .computeIfAbsent(
                                        new TierGroupKey(namespace, victim.reasoningChainId()),
                                        ignored -> new HashSet<>())
                                .add(victim.id());
                    }
                }
            }

            if (!reasoningChainsStillComplete(expectedChainMembers)) {
                return false;
            }

            long existingTokens = currentExisting == null ? 0L : currentExisting.getTokenCount();
            long projectedTokens = Math.addExact(
                    Math.subtractExact(simulatedTokens, existingTokens),
                    incomingFragment.getTokenCount());
            if (projectedTokens < 0L || projectedTokens > capacity) {
                return false;
            }

            long existingPinnedTokens = currentExisting == null
                    ? 0L
                    : pinManager.getIndexedPinnedTokenCount(currentExisting.getId());
            long effectiveProtectedTokens = Math.addExact(
                    Math.subtractExact(pinManager.getPinnedTokenCount(), existingPinnedTokens),
                    incomingFragment.getTokenCount());
            if (effectiveProtectedTokens > capacity) {
                return false;
            }

            long projectedNamespaceUsage = Math.addExact(
                    Math.subtractExact(
                            Math.subtractExact(
                                    l1Admin.namespaceTokenCount(namespace),
                                    existingTokens),
                            victimTokens),
                    incomingFragment.getTokenCount());
            long hardQuota = namespaceQuotaManager.hardQuotaPerNamespace(
                    capacity,
                    l1Admin.activeNamespaceCount());
            return projectedNamespaceUsage >= 0L && projectedNamespaceUsage <= hardQuota;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private boolean isScopedLocalReclaimPlan(
            AdmissionPlan plan,
            MemoryFragment incomingFragment,
            MemoryFragment currentExisting) {
        String namespace = incomingFragment.getNamespace();
        if (!plan.admitted()
                || plan.plannedEvictions().isEmpty()
                || namespace == null
                || namespace.isBlank()
                || (currentExisting != null
                        && !Objects.equals(namespace, currentExisting.getNamespace()))) {
            return false;
        }
        return plan.plannedEvictions().stream().allMatch(plannedEviction ->
                SCOPED_LOCAL_RECLAIM_REASONS.contains(plannedEviction.reason())
                        && Objects.equals(namespace, plannedEviction.triggerNamespace())
                        && plannedEviction.victims().stream()
                                .allMatch(victim -> Objects.equals(namespace, victim.namespace())));
    }

    private boolean reasoningChainsStillComplete(
            Map<TierGroupKey, Set<String>> expectedChainMembers) {
        for (Map.Entry<TierGroupKey, Set<String>> entry : expectedChainMembers.entrySet()) {
            LinkedHashSet<String> trackedMemberIds = tierGroupMembers.get(entry.getKey());
            if (trackedMemberIds == null) {
                return false;
            }
            Set<String> currentEvictableMemberIds = new HashSet<>();
            for (String memberId : trackedMemberIds) {
                MemoryFragment member = l1.peek(memberId).orElse(null);
                if (member == null
                        || !Objects.equals(entry.getKey().namespace(), member.getNamespace())
                        || !Objects.equals(entry.getKey().groupKey(), groupKey(member))) {
                    return false;
                }
                if (!member.isPinned()) {
                    currentEvictableMemberIds.add(memberId);
                }
            }
            if (!currentEvictableMemberIds.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean canCommitWithoutReclaimLocked(
            MemoryFragment incomingFragment,
            MemoryFragment existingFragment) {
        return evaluateNoReclaimAdmissionLocked(incomingFragment, existingFragment)
                == NoReclaimAdmissionDecision.COMMIT;
    }

    private NoReclaimAdmissionDecision evaluateNoReclaimAdmissionLocked(
            MemoryFragment incomingFragment,
            MemoryFragment existingFragment) {
        long capacity = l1.maxTokenCapacity();
        long requiredTokens = incomingFragment.getTokenCount();
        long existingTokens = existingFragment == null ? 0L : existingFragment.getTokenCount();

        long existingPinnedTokens = existingFragment == null
                ? 0L
                : pinManager.getIndexedPinnedTokenCount(existingFragment.getId());
        long effectiveProtectedTokens =
                pinManager.getPinnedTokenCount() - existingPinnedTokens + requiredTokens;
        if (effectiveProtectedTokens > capacity) {
            return NoReclaimAdmissionDecision.REJECT;
        }

        if (allocationChanged(existingFragment, incomingFragment)) {
            QuotaProjectionState quotaState =
                    quotaProjectionStateExcludingExisting(incomingFragment.getNamespace(), existingFragment);
            long hardQuota = namespaceQuotaManager.hardQuotaPerNamespace(
                    capacity,
                    quotaState.activeNamespaceCount());
            if (quotaState.focusNamespaceUsage() + requiredTokens > hardQuota) {
                return NoReclaimAdmissionDecision.PLAN_REQUIRED;
            }
        }

        if (existingFragment == null
                && incomingFragment.getNamespace() != null
                && !incomingFragment.getNamespace().isBlank()) {
            long currentTokens = l1.currentTokenCount();
            if (capacity == 0 || (double) currentTokens / capacity >= evictionThreshold) {
                return NoReclaimAdmissionDecision.PLAN_REQUIRED;
            }
        }

        if (l1.currentTokenCount() - existingTokens + requiredTokens > capacity) {
            return NoReclaimAdmissionDecision.PLAN_REQUIRED;
        }
        return NoReclaimAdmissionDecision.COMMIT;
    }

    private QuotaProjectionState quotaProjectionStateExcludingExisting(
            String focusNamespace,
            MemoryFragment existingFragment) {
        long focusNamespaceUsage = l1Admin.namespaceTokenCount(focusNamespace);
        int activeNamespaceCount = l1Admin.activeNamespaceCount();
        if (existingFragment != null
                && existingFragment.getNamespace() != null
                && !existingFragment.getNamespace().isBlank()) {
            if (Objects.equals(existingFragment.getNamespace(), focusNamespace)) {
                focusNamespaceUsage -= existingFragment.getTokenCount();
            }
            if (l1Admin.namespaceFragmentCount(existingFragment.getNamespace()) == 1) {
                activeNamespaceCount--;
            }
        }
        return new QuotaProjectionState(focusNamespaceUsage, activeNamespaceCount);
    }

    private void applyPlannedEviction(
            PlannedEviction plannedEviction,
            List<PendingPersistence> pendingPersistence) {
        List<MemoryFragment> removedFragments = new ArrayList<>(plannedEviction.victims().size());
        try {
            for (ResidentState victim : plannedEviction.victims()) {
                MemoryFragment fragment = l1.peek(victim.id()).orElseThrow();
                SemanticEvictionPolicy.EvictionCandidate scored =
                        evictionPolicy.scoreFragment(fragment, plannedEviction.representativeEmbedding());
                regretTracker.recordEviction(fragment, plannedEviction.reason());
                l1.remove(fragment.getId());
                removedFragments.add(fragment);
                pendingPersistence.add(new PendingPersistence(
                        fragment,
                        plannedEviction.reason(),
                        scored,
                        plannedEviction.triggerNamespace(),
                        plannedEviction.targetTokens()));
                pinManager.removePinIndex(fragment);
            }
        } finally {
            finalizeRemovedFragments(removedFragments);
        }
    }

    private void finalizeRemovedFragments(Collection<MemoryFragment> removedFragments) {
        if (removedFragments.isEmpty()) {
            return;
        }
        try {
            removeFromTierIndexes(removedFragments);
        } finally {
            markAdmissionStateChanged();
        }
    }

    private void recordTieredSelections(List<TieredSelection> selections) {
        selections.forEach(selection -> sloTracker.recordTieredSelection(
                selection.coldOnly(),
                selection.hotOnly(),
                selection.expanded()));
    }

    /** Remove an L1 entry and its admission indexes as one transaction. */
    public boolean removeFromL1(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) {
            return false;
        }
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            MemoryFragment existing = l1.peek(fragmentId).orElse(null);
            if (existing == null) {
                return false;
            }
            l1.remove(fragmentId);
            pinManager.removePinIndex(existing);
            reindexTierMembership(existing);
            markAdmissionStateChanged();
            return true;
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
        }
    }

    /** Remove a transient L1 entry without propagating it to durable tiers. */
    void removeTransientFromL1(String fragmentId) {
        removeFromL1(fragmentId);
    }

    /** Restore a transient entry's caller-visible pin state after background processing fails. */
    void refreshTransientPin(String fragmentId, Long pinnedUntil) {
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            l1.peek(fragmentId).ifPresent(fragment -> {
                pinManager.removePinIndex(fragment);
                fragment.setPinnedUntil(pinnedUntil);
                l1.put(fragment, false);
                pinManager.indexPin(fragment);
                reindexTierMembership(fragment);
                markAdmissionStateChanged();
            });
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
        }
    }

    public Optional<MemoryFragment> pinFragment(String fragmentId, long ttlMillis) {
        if (fragmentId == null || fragmentId.isBlank() || ttlMillis <= 0) {
            return Optional.empty();
        }
        Optional<MemoryFragment> located = pinManager.findFragment(fragmentId);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        MemoryFragment fragment;
        List<PendingPersistence> pendingPersistence = new ArrayList<>();
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            MemoryFragment existing = l1.peek(fragmentId).orElse(null);
            fragment = existing == null ? located.get() : existing;
            fragment.pinForMillis(ttlMillis);
            if (existing != null) {
                markAdmissionStateChanged();
            }
            if (allocationChanged(existing, fragment)) {
                enforceQuotaBeforeWrite(fragment, existing, pendingPersistence);
            }
            if (!ensureCapacityForAdmission(fragment, existing, "pin-update", pendingPersistence)) {
                return Optional.empty();
            }
            replaceResidentFragment(existing, fragment, false);
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
            persistAfterUnlock(pendingPersistence);
        }
        persistenceManager.persistAsync(fragment, "pin-update");
        return Optional.of(fragment);
    }

    public Optional<MemoryFragment> unpinFragment(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) {
            return Optional.empty();
        }
        Optional<MemoryFragment> located = pinManager.findFragment(fragmentId);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        MemoryFragment fragment;
        List<PendingPersistence> pendingPersistence = new ArrayList<>();
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            MemoryFragment existing = l1.peek(fragmentId).orElse(null);
            fragment = existing == null ? located.get() : existing;
            fragment.unpin();
            if (existing != null) {
                markAdmissionStateChanged();
            }
            if (allocationChanged(existing, fragment)) {
                enforceQuotaBeforeWrite(fragment, existing, pendingPersistence);
            }
            if (!ensureCapacityForAdmission(fragment, existing, "pin-update", pendingPersistence)) {
                return Optional.empty();
            }
            replaceResidentFragment(existing, fragment, false);
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
            persistAfterUnlock(pendingPersistence);
        }
        persistenceManager.persistAsync(fragment, "pin-update");
        return Optional.of(fragment);
    }

    public void clearExpiredPins() {
        List<MemoryFragment> cleared;
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            cleared = pinManager.clearExpiredPinsLocked();
            if (!cleared.isEmpty()) {
                markAdmissionStateChanged();
            }
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
        }
        cleared.forEach(fragment -> persistenceManager.persistAsync(fragment, "pin-expired"));
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
        List<PendingPersistence> pendingPersistence = new ArrayList<>();
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            Set<String> residentAtAdmissionStart = fragments.stream()
                    .map(MemoryFragment::getId)
                    .filter(fragmentId -> l1.peek(fragmentId).isPresent())
                    .collect(Collectors.toSet());
            boolean primaryAdmissionConsumed = primaryFragmentId != null
                    && residentAtAdmissionStart.contains(primaryFragmentId);
            for (MemoryFragment fragment : fragments) {
                boolean isPrimary = Objects.equals(primaryFragmentId, fragment.getId());
                if (residentAtAdmissionStart.contains(fragment.getId())) {
                    MemoryFragment existing = l1.peek(fragment.getId()).orElse(null);
                    if (existing != null && allocationChanged(existing, fragment)) {
                        enforceQuotaBeforeWrite(fragment, existing, pendingPersistence);
                    }
                    if (existing != null && ensureCapacityForAdmission(
                            fragment, existing, "page-fault-refresh", pendingPersistence)) {
                        replaceResidentFragment(existing, fragment, true);
                    }
                    if (isPrimary) {
                        primaryAdmissionConsumed = true;
                    }
                    continue;
                }
                MemoryFragment existing = l1.peek(fragment.getId()).orElse(null);
                if (existing != null) {
                    if (allocationChanged(existing, fragment)) {
                        enforceQuotaBeforeWrite(fragment, existing, pendingPersistence);
                    }
                    if (ensureCapacityForAdmission(fragment, existing, "page-fault-refresh", pendingPersistence)) {
                        replaceResidentFragment(existing, fragment, true);
                    }
                    if (isPrimary) {
                        primaryAdmissionConsumed = true;
                    }
                    continue;
                }
                if (isPrimary || (primaryFragmentId == null && !primaryAdmissionConsumed)) {
                    primaryAdmissionConsumed = true;
                    enforceQuotaBeforeWrite(fragment, null, pendingPersistence);
                    maybeEvictLocked(fragment.getNamespace(), fragment.getEmbedding(), pendingPersistence);
                    if (ensureCapacityForAdmission(fragment, null, "page-fault", pendingPersistence)) {
                        replaceResidentFragment(null, fragment, true);
                    }
                    continue;
                }
                if (canAdmitPageCompanionWithoutReclaim(fragment, pendingPersistence)) {
                    replaceResidentFragment(null, fragment, true);
                }
            }
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
            persistAfterUnlock(pendingPersistence);
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
        if (admissionLock.isHeldByCurrentThread()) {
            reindexTierMembershipLocked(fragment);
            return;
        }
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            reindexTierMembershipLocked(fragment);
            markAdmissionStateChanged();
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
        }
    }

    private void reindexTierMembershipLocked(MemoryFragment fragment) {
        if (fragment == null || fragment.getId() == null || fragment.getId().isBlank()) {
            return;
        }
        Set<TierGroupKey> affectedGroups = new LinkedHashSet<>(2);
        removeTrackedTierMembership(fragment.getId(), affectedGroups);

        MemoryFragment resident = l1.peek(fragment.getId()).orElse(null);
        if (isTierIndexable(resident)) {
            TierGroupKey currentGroup =
                    new TierGroupKey(resident.getNamespace(), groupKey(resident));
            tierGroupByFragment.put(resident.getId(), currentGroup);
            tierGroupMembers
                    .computeIfAbsent(currentGroup, ignored -> new LinkedHashSet<>())
                    .add(resident.getId());
            affectedGroups.add(currentGroup);
        }
        affectedGroups.forEach(this::refreshTierGroup);
    }

    void removeFromTierIndexes(MemoryFragment fragment) {
        if (fragment == null || fragment.getId() == null || fragment.getId().isBlank()) {
            return;
        }
        removeFromTierIndexes(List.of(fragment));
    }

    private void removeFromTierIndexes(Collection<MemoryFragment> fragments) {
        Set<TierGroupKey> affectedGroups = new LinkedHashSet<>();
        for (MemoryFragment fragment : fragments) {
            if (fragment == null || fragment.getId() == null || fragment.getId().isBlank()) {
                continue;
            }
            removeTrackedTierMembership(fragment.getId(), affectedGroups);
        }
        affectedGroups.forEach(this::refreshTierGroup);
    }

    boolean isHot(MemoryFragment fragment) {
        return System.currentTimeMillis() - fragment.getLastAccessTime() <= hotTierRecencyWindowMillis;
    }

    // ---- Internal: quota and capacity enforcement ----

    private void enforceQuotaBeforeWrite(
            MemoryFragment incomingFragment,
            MemoryFragment existingFragment,
            List<PendingPersistence> pendingPersistence) {
        if (l1Admin == null) {
            return;
        }
        List<MemoryFragment> allFragments = l1Admin.allFragments().stream()
                .filter(fragment -> existingFragment == null
                        || !Objects.equals(fragment.getId(), existingFragment.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
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
                allFragments.stream()
                        .filter(fragment -> Objects.equals(fragment.getNamespace(), incomingFragment.getNamespace()))
                        .toList(),
                incomingFragment.getEmbedding(),
                requiredTokens);
        long released = evictCandidatesUntil(
                ownCandidates,
                incomingFragment.getNamespace(),
                requiredTokens,
                "quota-self-reclaim",
                0L,
                existingFragment == null ? null : existingFragment.getId(),
                pendingPersistence);
        if (released >= requiredTokens) {
            return;
        }

        long remainingRequired = requiredTokens - released;
        for (String otherNamespace : namespaceQuotaManager.evictionPriorityNamespaces(
                allFragments, l1.maxTokenCapacity(), incomingFragment.getNamespace())) {
            List<MemoryFragment> namespaceFragments = allFragments.stream()
                    .filter(fragment -> Objects.equals(fragment.getNamespace(), otherNamespace))
                    .toList();
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
                    currentSnapshot.hardQuotaPerNamespace(),
                    existingFragment == null ? null : existingFragment.getId(),
                    pendingPersistence);
            remainingRequired -= evicted;
            if (remainingRequired <= 0) {
                break;
            }
        }
    }

    private boolean canAdmitPageCompanionWithoutReclaim(
            MemoryFragment incomingFragment,
            List<PendingPersistence> pendingPersistence) {
        queueClearedPinsForPersistence(pendingPersistence);
        if (l1Admin == null) {
            return true;
        }
        long capacity = l1.maxTokenCapacity();
        long requiredTokens = incomingFragment.getTokenCount();
        if (pinManager.getPinnedTokenCount() + requiredTokens > capacity) {
            return false;
        }
        List<MemoryFragment> allFragments = new ArrayList<>(l1Admin.allFragments());
        NamespaceQuotaManager.QuotaSnapshot snapshot = namespaceQuotaManager.snapshot(
                allFragments,
                capacity,
                incomingFragment.getNamespace());
        long projectedUsage = snapshot.focusNamespaceUsage() + requiredTokens;
        if (projectedUsage > snapshot.hardQuotaPerNamespace()) {
            return false;
        }
        return l1.currentTokenCount() + requiredTokens <= capacity;
    }

    private boolean ensureCapacityForAdmission(
            MemoryFragment incomingFragment,
            MemoryFragment existingFragment,
            String context,
            List<PendingPersistence> pendingPersistence) {
        queueClearedPinsForPersistence(pendingPersistence);
        if (l1Admin == null) {
            return true;
        }
        long capacity = l1.maxTokenCapacity();
        long pinnedTokens = pinManager.getPinnedTokenCount();
        long requiredTokens = incomingFragment.getTokenCount();
        long existingTokens = existingFragment == null ? 0L : existingFragment.getTokenCount();
        long existingPinnedTokens = existingFragment == null
                ? 0L
                : pinManager.getIndexedPinnedTokenCount(existingFragment.getId());
        long effectiveProtectedTokens = pinnedTokens - existingPinnedTokens + requiredTokens;
        if (effectiveProtectedTokens > capacity) {
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

        long gap = (l1.currentTokenCount() - existingTokens + requiredTokens) - capacity;
        if (gap <= 0) {
            return true;
        }

        long released = reclaimAdmissionGap(
                incomingFragment,
                existingFragment == null ? null : existingFragment.getId(),
                gap,
                pendingPersistence);
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

    private long reclaimAdmissionGap(
            MemoryFragment incomingFragment,
            String excludedFragmentId,
            long gap,
            List<PendingPersistence> pendingPersistence) {
        List<SemanticEvictionPolicy.EvictionCandidate> localCandidates = rankTieredCandidates(
                l1.getAll(incomingFragment.getNamespace()).stream()
                        .filter(fragment -> !Objects.equals(fragment.getId(), excludedFragmentId))
                        .toList(),
                incomingFragment.getEmbedding(),
                gap);
        long released = evictCandidatesUntil(
                localCandidates,
                incomingFragment.getNamespace(),
                gap,
                "capacity-self-reclaim",
                0L,
                excludedFragmentId,
                pendingPersistence);
        if (released >= gap) {
            return released;
        }

        if (l1Admin == null) {
            return released;
        }
        long remaining = gap - released;
        List<MemoryFragment> allFragments = new ArrayList<>(l1Admin.allFragments());
        List<SemanticEvictionPolicy.EvictionCandidate> globalCandidates = rankTieredCandidates(
                allFragments.stream()
                        .filter(fragment -> !Objects.equals(fragment.getId(), excludedFragmentId))
                        .filter(fragment -> !Objects.equals(fragment.getNamespace(), incomingFragment.getNamespace()))
                        .toList(),
                incomingFragment.getEmbedding(),
                remaining);
        return released + evictCandidatesUntil(
                globalCandidates,
                incomingFragment.getNamespace(),
                remaining,
                "capacity-global-reclaim",
                0L,
                excludedFragmentId,
                pendingPersistence);
    }

    private boolean allocationChanged(MemoryFragment existingFragment, MemoryFragment incomingFragment) {
        return existingFragment == null
                || existingFragment.getTokenCount() != incomingFragment.getTokenCount()
                || !Objects.equals(existingFragment.getNamespace(), incomingFragment.getNamespace());
    }

    private void replaceResidentFragment(
            MemoryFragment existingFragment,
            MemoryFragment incomingFragment,
            boolean recordAccess) {
        if (existingFragment != null) {
            pinManager.removePinIndex(existingFragment);
        }
        l1.put(incomingFragment, recordAccess);
        pinManager.indexPin(incomingFragment);
        reindexTierMembership(incomingFragment);
        markAdmissionStateChanged();
    }

    private void queueClearedPinsForPersistence(List<PendingPersistence> pendingPersistence) {
        List<MemoryFragment> clearedPins = pinManager.clearExpiredPinsLocked();
        for (MemoryFragment fragment : clearedPins) {
            pendingPersistence.add(PendingPersistence.pinUpdate(fragment, "pin-expired"));
        }
        if (!clearedPins.isEmpty()) {
            markAdmissionStateChanged();
        }
    }

    private void persistAfterUnlock(List<PendingPersistence> pendingPersistence) {
        Map<String, List<MemoryFragment>> fragmentsByReason = new LinkedHashMap<>();
        for (PendingPersistence pending : pendingPersistence) {
            if (pending.scoredCandidate() != null) {
                evictionDecisionLogger.logSemanticDecision(
                        pending.scoredCandidate(),
                        pending.triggerNamespace(),
                        pending.targetTokens());
            }
            fragmentsByReason
                    .computeIfAbsent(pending.reason(), ignored -> new ArrayList<>())
                    .add(pending.fragment());
        }
        for (Map.Entry<String, List<MemoryFragment>> entry : fragmentsByReason.entrySet()) {
            if (entry.getValue().size() == 1) {
                persistenceManager.persistAsync(entry.getValue().getFirst(), entry.getKey());
            } else {
                persistenceManager.persistAsyncBatch(entry.getValue(), entry.getKey());
            }
        }
    }

    // ---- Internal: eviction execution ----

    private long evictCandidatesUntil(
            List<SemanticEvictionPolicy.EvictionCandidate> candidates,
            String triggerNamespace,
            long targetTokens,
            String reason,
            List<PendingPersistence> pendingPersistence) {
        return evictCandidatesUntil(
                candidates,
                triggerNamespace,
                targetTokens,
                reason,
                0L,
                null,
                pendingPersistence);
    }

    private long evictCandidatesUntil(
            List<SemanticEvictionPolicy.EvictionCandidate> candidates,
            String triggerNamespace,
            long targetTokens,
            String reason,
            long minRemainingTokens,
            List<PendingPersistence> pendingPersistence) {
        return evictCandidatesUntil(
                candidates,
                triggerNamespace,
                targetTokens,
                reason,
                minRemainingTokens,
                null,
                pendingPersistence);
    }

    private long evictCandidatesUntil(
            List<SemanticEvictionPolicy.EvictionCandidate> candidates,
            String triggerNamespace,
            long targetTokens,
            String reason,
            long minRemainingTokens,
            String excludedFragmentId,
            List<PendingPersistence> pendingPersistence) {
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
                    excludedFragmentId,
                    namespaceTokenUsage,
                    pendingPersistence);
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
            String excludedFragmentId,
            Map<String, Long> namespaceTokenUsage,
            List<PendingPersistence> pendingPersistence) {
        if (candidate.pinned()) {
            return 0;
        }
        String groupId = candidate.reasoningChainId();
        if (groupId != null && !groupId.isBlank() && !evictedGroups.add(groupId)) {
            return 0;
        }

        List<MemoryFragment> evictionGroup = resolveEvictionGroup(candidate, excludedFragmentId);
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
        List<MemoryFragment> removedFragments = new ArrayList<>(evictionGroup.size());
        try {
            for (MemoryFragment fragment : evictionGroup) {
                SemanticEvictionPolicy.EvictionCandidate scored = evictionPolicy.scoreFragment(fragment, candidate.fragment().getEmbedding());
                regretTracker.recordEviction(fragment, reason);
                l1.remove(fragment.getId());
                removedFragments.add(fragment);
                pendingPersistence.add(new PendingPersistence(
                        fragment,
                        reason,
                        scored,
                        triggerNamespace,
                        targetTokens));
                pinManager.removePinIndex(fragment);
                released += fragment.getTokenCount();
            }
        } finally {
            finalizeRemovedFragments(removedFragments);
        }
        long releasedTokens = released;
        namespaceTokenUsage.compute(namespace, (key, value) -> Math.max(0L, (value == null ? 0L : value) - releasedTokens));
        return released;
    }

    private List<MemoryFragment> resolveEvictionGroup(
            SemanticEvictionPolicy.EvictionCandidate candidate,
            String excludedFragmentId) {
        String groupId = candidate.reasoningChainId();
        if (groupId == null || groupId.isBlank()) {
            MemoryFragment fragment = l1.peek(candidate.fragment().getId()).orElse(candidate.fragment());
            if (Objects.equals(fragment.getId(), excludedFragmentId)) {
                return List.of();
            }
            if (fragment.clearExpiredPin() || fragment.isPinned()) {
                if (fragment.isPinned()) {
                    return List.of();
                }
                l1.put(fragment, false);
                pinManager.indexPin(fragment);
                reindexTierMembership(fragment);
                markAdmissionStateChanged();
            }
            return fragment.isPinned() ? List.of() : List.of(fragment);
        }
        List<MemoryFragment> group = l1.getAll(candidate.fragment().getNamespace()).stream()
                .map(fragment -> {
                    if (fragment.clearExpiredPin()) {
                        l1.put(fragment, false);
                        pinManager.indexPin(fragment);
                        reindexTierMembership(fragment);
                        markAdmissionStateChanged();
                    }
                    return fragment;
                })
                .filter(fragment -> groupId.equals(fragment.getReasoningChainId()))
                .toList();
        if (group.stream().anyMatch(fragment -> Objects.equals(fragment.getId(), excludedFragmentId))) {
            return List.of();
        }
        return group.stream()
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
        RankedTieredCandidates ranked = rankTieredCandidatesDetailed(
                candidates,
                queryEmbedding,
                targetTokens,
                profile,
                true);
        if (ranked.selection() != null) {
            recordTieredSelections(List.of(ranked.selection()));
        }
        return ranked.candidates();
    }

    private RankedTieredCandidates rankTieredCandidatesDetailed(
            Collection<MemoryFragment> candidates,
            float[] queryEmbedding,
            long targetTokens,
            AdaptiveWeightProfile profile,
            boolean useLiveTierIndexes) {
        List<MemoryFragment> filtered = candidates.stream()
                .filter(Objects::nonNull)
                .filter(fragment -> !fragment.isPinned())
                .toList();
        if (filtered.isEmpty()) {
            return new RankedTieredCandidates(List.of(), null);
        }
        TieredCandidatePool pool = useLiveTierIndexes
                ? resolveTieredCandidatePool(filtered)
                : buildTieredCandidatePool(filtered);
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
            return new RankedTieredCandidates(
                    ranked,
                    new TieredSelection(coldOnly, hotOnly, false));
        }
        List<MemoryFragment> expanded = new ArrayList<>(pool.coldTier());
        expanded.addAll(limitHotTier(pool.hotTier(), targetTokens - coldCoverage));
        return new RankedTieredCandidates(
                applyRegretAwareOrdering(
                        evictionPolicy.rankCandidates(expanded, queryEmbedding, profile),
                        targetTokens),
                new TieredSelection(false, false, true));
    }

    List<String> rankEvictionForEvaluation(
            Collection<MemoryFragment> candidates,
            float[] queryEmbedding,
            AdaptiveWeightProfile profile) {
        long lockAcquiredNanos = acquireAdmissionLock();
        try {
            return rankTieredCandidates(candidates, queryEmbedding, Long.MAX_VALUE, profile).stream()
                    .map(candidate -> candidate.fragment().getId())
                    .toList();
        } finally {
            releaseAdmissionLock(lockAcquiredNanos);
        }
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
        if (l1Admin == null) {
            return;
        }
        hotTierIndex.clear();
        coldTierIndex.clear();
        tierGroupByFragment.clear();
        tierGroupMembers.clear();
        tierRefByGroup.clear();
        for (MemoryFragment fragment : l1Admin.allFragments()) {
            if (!isTierIndexable(fragment)) {
                continue;
            }
            TierGroupKey group = new TierGroupKey(fragment.getNamespace(), groupKey(fragment));
            tierGroupByFragment.put(fragment.getId(), group);
            tierGroupMembers
                    .computeIfAbsent(group, ignored -> new LinkedHashSet<>())
                    .add(fragment.getId());
        }
        List.copyOf(tierGroupMembers.keySet()).forEach(this::refreshTierGroup);
    }

    private void removeTrackedTierMembership(
            String fragmentId,
            Set<TierGroupKey> affectedGroups) {
        TierGroupKey previousGroup = tierGroupByFragment.remove(fragmentId);
        if (previousGroup == null) {
            return;
        }
        LinkedHashSet<String> members = tierGroupMembers.get(previousGroup);
        if (members != null) {
            members.remove(fragmentId);
            if (members.isEmpty()) {
                tierGroupMembers.remove(previousGroup);
            }
        }
        affectedGroups.add(previousGroup);
    }

    private void refreshTierGroup(TierGroupKey group) {
        TieredGroupRef previousRef = tierRefByGroup.remove(group);
        if (previousRef != null) {
            removeTierRef(group.namespace(), previousRef);
        }

        LinkedHashSet<String> memberIds = tierGroupMembers.get(group);
        if (memberIds == null || memberIds.isEmpty()) {
            tierGroupMembers.remove(group);
            return;
        }

        List<MemoryFragment> members = new ArrayList<>(memberIds.size());
        Iterator<String> iterator = memberIds.iterator();
        while (iterator.hasNext()) {
            String fragmentId = iterator.next();
            MemoryFragment resident = l1.peek(fragmentId).orElse(null);
            if (!isTierIndexable(resident)
                    || !Objects.equals(group.namespace(), resident.getNamespace())
                    || !Objects.equals(group.groupKey(), groupKey(resident))) {
                iterator.remove();
                tierGroupByFragment.remove(fragmentId, group);
                continue;
            }
            members.add(resident);
        }
        if (members.isEmpty()) {
            tierGroupMembers.remove(group);
            return;
        }

        TieredGroupRef updatedRef = TieredGroupRef.of(members, hotTierRecencyWindowMillis);
        if (updatedRef == null) {
            return;
        }
        tierRefByGroup.put(group, updatedRef);
        ConcurrentMap<String, NavigableSet<TieredGroupRef>> targetIndex =
                updatedRef.hot() ? hotTierIndex : coldTierIndex;
        targetIndex
                .computeIfAbsent(group.namespace(), ignored -> new TreeSet<>())
                .add(updatedRef);
    }

    private void removeTierRef(String namespace, TieredGroupRef ref) {
        ConcurrentMap<String, NavigableSet<TieredGroupRef>> sourceIndex =
                ref.hot() ? hotTierIndex : coldTierIndex;
        NavigableSet<TieredGroupRef> namespaceIndex = sourceIndex.get(namespace);
        if (namespaceIndex == null) {
            return;
        }
        namespaceIndex.remove(ref);
        if (namespaceIndex.isEmpty()) {
            sourceIndex.remove(namespace, namespaceIndex);
        }
    }

    private boolean isTierIndexable(MemoryFragment fragment) {
        return fragment != null
                && fragment.getId() != null
                && !fragment.getId().isBlank()
                && fragment.getNamespace() != null
                && !fragment.getNamespace().isBlank();
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

    private MemoryFragment freezeFragment(MemoryFragment fragment) {
        if (fragment == null) {
            return null;
        }
        return MemoryFragment.builder()
                .id(fragment.getId())
                .namespace(fragment.getNamespace())
                .content(fragment.getContent())
                .embedding(cloneArray(fragment.getEmbedding()))
                .l2Embedding(cloneArray(fragment.getL2Embedding()))
                .tokenCount(fragment.getTokenCount())
                .importance(fragment.getImportance())
                .lastAccessTime(fragment.getLastAccessTime())
                .createdAt(fragment.getCreatedAt())
                .tags(fragment.getTags() == null ? null : List.copyOf(fragment.getTags()))
                .reasoningChainId(fragment.getReasoningChainId())
                .pinnedUntil(fragment.getPinnedUntil())
                .build();
    }

    private static float[] cloneArray(float[] values) {
        return values == null ? null : values.clone();
    }

    private static ReentrantLock[] createAdmissionPlanningGates() {
        ReentrantLock[] gates = new ReentrantLock[ADMISSION_PLANNING_GATE_STRIPES];
        Arrays.setAll(gates, ignored -> new ReentrantLock(true));
        return gates;
    }

    private ReentrantLock planningGateFor(String namespace) {
        int hash = namespace == null ? 0 : namespace.hashCode();
        return admissionPlanningGates[Math.floorMod(hash, admissionPlanningGates.length)];
    }

    private boolean acquirePlanningGate(ReentrantLock planningGate) {
        boolean contended = planningGate.isLocked() || planningGate.hasQueuedThreads();
        long waitStartedNanos = System.nanoTime();
        planningGate.lock();
        sloTracker.recordAdmissionPlanningGateWait(System.nanoTime() - waitStartedNanos);
        return contended;
    }

    private long acquireAdmissionLock() {
        long waitStartedNanos = System.nanoTime();
        admissionLock.lock();
        long acquiredNanos = System.nanoTime();
        sloTracker.recordAdmissionLockWait(acquiredNanos - waitStartedNanos);
        return acquiredNanos;
    }

    private void releaseAdmissionLock(long acquiredNanos) {
        releaseAdmissionLock(acquiredNanos, AdmissionLockPhase.OTHER);
    }

    private void releaseAdmissionLock(long acquiredNanos, AdmissionLockPhase phase) {
        long holdNanos = System.nanoTime() - acquiredNanos;
        admissionLock.unlock();
        sloTracker.recordAdmissionLockHold(holdNanos);
        if (phase == AdmissionLockPhase.DETAILED_SNAPSHOT) {
            sloTracker.recordAdmissionDetailedSnapshotLockHold(holdNanos);
        } else if (phase == AdmissionLockPhase.OPTIMISTIC_COMMIT) {
            sloTracker.recordAdmissionCommitLockHold(holdNanos);
        }
    }

    private void markAdmissionStateChanged() {
        if (!admissionLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Admission state changed without holding admissionLock");
        }
        admissionEpoch++;
    }

    // ---- Public inner types ----

    private enum AdmissionCommitResult {
        ADMITTED,
        REJECTED,
        CONFLICT
    }

    private enum DirectAdmissionResult {
        ADMITTED,
        REJECTED,
        ESCALATED
    }

    private enum NoReclaimAdmissionDecision {
        COMMIT,
        REJECT,
        PLAN_REQUIRED
    }

    private enum AdmissionLockPhase {
        SUMMARY_SNAPSHOT,
        DETAILED_SNAPSHOT,
        OPTIMISTIC_COMMIT,
        OTHER
    }

    private record AdmissionSnapshot(
            long epoch,
            List<MemoryFragment> residents,
            boolean residentsCaptured,
            MemoryFragment incoming,
            MemoryFragment existing,
            long currentTokens,
            long capacity,
            long pinnedTokens,
            long existingPinnedTokens,
            long focusNamespaceUsage,
            int activeNamespaceCount) {
    }

    private record AdmissionPlan(
            AdmissionSnapshot snapshot,
            boolean admitted,
            boolean fastCommitEligible,
            boolean detailedSnapshotRequired,
            List<PlannedEviction> plannedEvictions,
            List<TieredSelection> tieredSelections) {
    }

    private record QuotaProjectionState(
            long focusNamespaceUsage,
            int activeNamespaceCount) {
    }

    private record TierGroupKey(
            String namespace,
            String groupKey) {
    }

    private record PlannedEviction(
            List<ResidentState> victims,
            String reason,
            String triggerNamespace,
            long targetTokens,
            float[] representativeEmbedding) {
    }

    private record RankedTieredCandidates(
            List<SemanticEvictionPolicy.EvictionCandidate> candidates,
            TieredSelection selection) {
    }

    private record TieredSelection(boolean coldOnly, boolean hotOnly, boolean expanded) {
    }

    private record ResidentState(
            String id,
            String namespace,
            int tokenCount,
            double importance,
            long lastAccessTime,
            String reasoningChainId,
            Long pinnedUntil,
            float[] embedding,
            float[] l2Embedding) {

        private static ResidentState of(MemoryFragment fragment) {
            if (fragment == null) {
                return null;
            }
            return new ResidentState(
                    fragment.getId(),
                    fragment.getNamespace(),
                    fragment.getTokenCount(),
                    fragment.getImportance(),
                    fragment.getLastAccessTime(),
                    fragment.getReasoningChainId(),
                    fragment.getPinnedUntil(),
                    cloneArray(fragment.getEmbedding()),
                    cloneArray(fragment.getL2Embedding()));
        }

        private boolean matches(MemoryFragment fragment) {
            return fragment != null
                    && Objects.equals(id, fragment.getId())
                    && Objects.equals(namespace, fragment.getNamespace())
                    && tokenCount == fragment.getTokenCount()
                    && Double.compare(importance, fragment.getImportance()) == 0
                    && lastAccessTime == fragment.getLastAccessTime()
                    && Objects.equals(reasoningChainId, fragment.getReasoningChainId())
                    && Objects.equals(pinnedUntil, fragment.getPinnedUntil())
                    && Arrays.equals(embedding, fragment.getEmbedding())
                    && Arrays.equals(l2Embedding, fragment.getL2Embedding());
        }

        private static boolean matchesNullable(ResidentState expected, MemoryFragment actual) {
            return expected == null ? actual == null : expected.matches(actual);
        }
    }

    private static final class AdmissionPlanningState {
        private final LinkedHashMap<String, MemoryFragment> residents = new LinkedHashMap<>();
        private final List<PlannedEviction> plannedEvictions = new ArrayList<>();
        private final List<TieredSelection> tieredSelections = new ArrayList<>();
        private boolean fastCommitEligible = true;

        private AdmissionPlanningState(List<MemoryFragment> snapshotResidents) {
            snapshotResidents.forEach(fragment -> residents.put(fragment.getId(), fragment));
        }

        private MemoryFragment resident(String fragmentId) {
            return residents.get(fragmentId);
        }

        private List<MemoryFragment> residents() {
            return List.copyOf(residents.values());
        }

        private List<MemoryFragment> namespaceResidents(String namespace) {
            return residents.values().stream()
                    .filter(fragment -> Objects.equals(namespace, fragment.getNamespace()))
                    .toList();
        }

        private long currentTokenCount() {
            return residents.values().stream().mapToLong(MemoryFragment::getTokenCount).sum();
        }

        private void remove(String fragmentId) {
            residents.remove(fragmentId);
        }

        private void requireStrictCommit() {
            fastCommitEligible = false;
        }
    }

    private record PendingPersistence(
            MemoryFragment fragment,
            String reason,
            SemanticEvictionPolicy.EvictionCandidate scoredCandidate,
            String triggerNamespace,
            long targetTokens) {

        private static PendingPersistence pinUpdate(MemoryFragment fragment, String reason) {
            return new PendingPersistence(fragment, reason, null, null, 0L);
        }
    }

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
