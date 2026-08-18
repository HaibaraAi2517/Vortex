package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.l1.CaffeineHotStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link TieredEvictionCoordinator}.
 *
 * Verifies eviction, admission, quota enforcement, tier indexing,
 * group-based reasoning chain handling, and pin protection.
 */
class TieredEvictionCoordinatorTest {

    private static final MemorySloTracker SLO_TRACKER = new MemorySloTracker(new SimpleMeterRegistry());
    private static final AdaptiveWeightLearner WEIGHT_LEARNER =
            new AdaptiveWeightLearner(new ShadowEvaluationTracker(0.20, 14), 0.05, 100, 0.3, 0.5, 0.2);

    private CaffeineHotStore l1;
    private FakeL2WarmStore l2;
    private FakeL3ColdStore l3;
    private EmbeddingService embedding;
    private SemanticEvictionPolicy evictionPolicy;
    private NamespaceQuotaManager quotaManager;
    private EvictionDecisionLogger decisionLogger;
    private EvictionRegretTracker regretTracker;
    private FragmentPersistenceManager persistenceManager;
    private FragmentPinManager pinManager;
    private TieredEvictionCoordinator tec;

    @BeforeEach
    void setUp() {
        l1 = new CaffeineHotStore(256);
        l2 = new FakeL2WarmStore(4);
        l3 = new FakeL3ColdStore();
        embedding = new FixedEmbeddingService(4);

        evictionPolicy = new SemanticEvictionPolicy(0.3, 0.5, 0.2);
        quotaManager = new NamespaceQuotaManager(0.25, 0.15, 16);
        decisionLogger = new EvictionDecisionLogger(SLO_TRACKER);
        regretTracker = new EvictionRegretTracker(3_600_000L, System::currentTimeMillis);

        persistenceManager = createPersistenceManager();

        ObjectProvider<EmbeddingService> cloudProvider = emptyProvider();
        pinManager = new FragmentPinManager(l1, l2, l3, persistenceManager, embedding, cloudProvider, null);

        tec = new TieredEvictionCoordinator(
                l1, evictionPolicy, quotaManager, decisionLogger, regretTracker,
                SLO_TRACKER, persistenceManager, WEIGHT_LEARNER, pinManager,
                0.85, 300_000, 64, 2);
        pinManager.setEvictionCoordinator(tec);
    }

    // ========================================================================
    // groupKeyOf
    // ========================================================================

    @Test
    void groupKeyOfReturnsChainIdWhenSet() {
        MemoryFragment fragment = fragment("f1", "ns", "content", List.of(), 4);
        fragment.setReasoningChainId("chain-1");

        String key = TieredEvictionCoordinator.groupKeyOf(fragment);

        assertThat(key).isEqualTo("chain-1");
    }

    @Test
    void groupKeyOfReturnsSelfKeyWhenChainIdIsNull() {
        MemoryFragment fragment = fragment("f1", "ns", "content", List.of(), 4);

        String key = TieredEvictionCoordinator.groupKeyOf(fragment);

        assertThat(key).startsWith("__self__:");
        assertThat(key).contains("f1");
    }

    @Test
    void groupKeyOfReturnsSelfKeyWhenChainIdIsBlank() {
        MemoryFragment fragment = fragment("f2", "ns", "content", List.of(), 4);
        fragment.setReasoningChainId("   ");

        String key = TieredEvictionCoordinator.groupKeyOf(fragment);

        assertThat(key).startsWith("__self__:");
    }

    // ========================================================================
    // isHot
    // ========================================================================

    @Test
    void isHotReturnsTrueForRecentAccess() {
        MemoryFragment fragment = fragment("hot", "ns", "recent", List.of(), 4);
        fragment.setLastAccessTime(System.currentTimeMillis());

        assertThat(tec.isHot(fragment)).isTrue();
    }

    @Test
    void isHotReturnsFalseForOldAccess() {
        MemoryFragment fragment = fragment("cold", "ns", "old", List.of(), 4);
        fragment.setLastAccessTime(System.currentTimeMillis() - 600_000L);

        assertThat(tec.isHot(fragment)).isFalse();
    }

    // ========================================================================
    // computeNamespaceTokenUsage
    // ========================================================================

    @Test
    void computeNamespaceTokenUsageSumsTokensCorrectly() {
        MemoryFragment a1 = fragment("a1", "ns-a", "aaaa", List.of(), 4);
        a1.setTokenCount(10);
        MemoryFragment a2 = fragment("a2", "ns-a", "bbbb", List.of(), 4);
        a2.setTokenCount(20);
        MemoryFragment b1 = fragment("b1", "ns-b", "cccc", List.of(), 4);
        b1.setTokenCount(5);

        l1.put(a1);
        l1.put(a2);
        l1.put(b1);

        Map<String, Long> usage = tec.computeNamespaceTokenUsage(l1.allFragments());

        assertThat(usage).containsEntry("ns-a", 30L);
        assertThat(usage).containsEntry("ns-b", 5L);
    }

    // ========================================================================
    // maybeEvict: no-op conditions
    // ========================================================================

    @Test
    void maybeEvictDoesNothingWhenBelowThreshold() {
        MemoryFragment fragment = fragment("f1", "ns", "small", List.of(), 4);
        fragment.setTokenCount(4);
        l1.put(fragment);

        tec.maybeEvict("ns", vector(4));

        assertThat(l1.peek("f1")).isPresent();
    }

    @Test
    void maybeEvictDoesNothingForNullNamespace() {
        tec.maybeEvict(null, vector(4));
        // no exception
    }

    @Test
    void maybeEvictDoesNothingForBlankNamespace() {
        tec.maybeEvict("   ", vector(4));
        // no exception
    }

    // ========================================================================
    // maybeEvict: eviction behavior
    // ========================================================================

    @Test
    void maybeEvictEvictsLowScoreFragmentsAboveThreshold() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(50);
        FakeL2WarmStore fl2 = new FakeL2WarmStore(4);
        FakeL3ColdStore fl3 = new FakeL3ColdStore();
        FragmentPersistenceManager pm = createPersistenceManager(fl2, fl3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, fl2, fl3, pm, embedding, emptyProvider(), null);

        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                0.5, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        long now = System.currentTimeMillis();
        MemoryFragment low = fragment("low", "ns", "low-importance", List.of(), 4);
        low.setTokenCount(20);
        low.setImportance(0.0);
        low.setLastAccessTime(now - 600_000L);
        MemoryFragment high = fragment("high", "ns", "high-importance", List.of(), 4);
        high.setTokenCount(20);
        high.setImportance(0.9);
        high.setLastAccessTime(now);

        smallL1.put(low, false);
        smallL1.put(high, false);

        localTec.maybeEvict("ns", vector(4));

        assertThat(smallL1.peek("low")).isEmpty();
        assertThat(smallL1.peek("high")).isPresent();
    }

    @Test
    void maybeEvictEvictsColdTierBeforeHotTier() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(100);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);

        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                0.5, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        long now = System.currentTimeMillis();
        MemoryFragment cold = fragment("cold", "ns", "cold", List.of(), 4);
        cold.setTokenCount(30);
        cold.setImportance(0.0);
        cold.setLastAccessTime(now - 600_000L);
        MemoryFragment hot = fragment("hot", "ns", "hot", List.of(), 4);
        hot.setTokenCount(30);
        hot.setImportance(0.0);
        hot.setLastAccessTime(now);
        MemoryFragment survivor = fragment("survivor", "ns", "survivor", List.of(), 4);
        survivor.setTokenCount(30);
        survivor.setImportance(0.9);
        survivor.setLastAccessTime(now);

        smallL1.put(cold, false);
        smallL1.put(hot, false);
        smallL1.put(survivor, false);

        localTec.maybeEvict("ns", vector(4));

        assertThat(smallL1.peek("cold")).isEmpty();
        assertThat(smallL1.peek("hot")).isPresent();
        assertThat(smallL1.peek("survivor")).isPresent();
    }

    @Test
    void maybeEvictTreatsReasoningChainAsSingleHotTierUnit() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(160);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);

        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                0.5, 60_000L, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        long now = System.currentTimeMillis();
        MemoryFragment coldSolo = fragment("cold-solo", "ns", "cold-solo", List.of(), 4);
        coldSolo.setTokenCount(40);
        coldSolo.setImportance(0.0);
        coldSolo.setLastAccessTime(now - 600_000L);
        MemoryFragment chainOld = fragment("chain-old", "ns", "chain-old", List.of(), 4);
        chainOld.setReasoningChainId("chain-hot");
        chainOld.setTokenCount(20);
        chainOld.setImportance(0.0);
        chainOld.setLastAccessTime(now - 600_000L);
        MemoryFragment chainFresh = fragment("chain-fresh", "ns", "chain-fresh", List.of(), 4);
        chainFresh.setReasoningChainId("chain-hot");
        chainFresh.setTokenCount(20);
        chainFresh.setImportance(0.0);
        chainFresh.setLastAccessTime(now);
        MemoryFragment hotSolo = fragment("hot-solo", "ns", "hot-solo", List.of(), 4);
        hotSolo.setTokenCount(40);
        hotSolo.setImportance(0.9);
        hotSolo.setLastAccessTime(now);

        smallL1.put(coldSolo, false);
        smallL1.put(chainOld, false);
        smallL1.put(chainFresh, false);
        smallL1.put(hotSolo, false);
        localTec.rebalanceTierIndexes();

        localTec.maybeEvict("ns", vector(4));

        assertThat(smallL1.peek("cold-solo")).isEmpty();
        assertThat(smallL1.peek("chain-old")).isPresent();
        assertThat(smallL1.peek("chain-fresh")).isPresent();
        assertThat(smallL1.peek("hot-solo")).isPresent();
    }

    @Test
    void pinnedFragmentsAreNotEvicted() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(50);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);

        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                0.5, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment pinned = fragment("pinned", "ns", "pinned", List.of(), 4);
        pinned.setTokenCount(20);
        pinned.pinForMillis(60_000L);
        MemoryFragment low = fragment("low", "ns", "low", List.of(), 4);
        low.setTokenCount(20);
        low.setImportance(0.0);
        MemoryFragment survivor = fragment("survivor", "ns", "survivor", List.of(), 4);
        survivor.setTokenCount(20);
        survivor.setImportance(0.9);

        smallL1.put(pinned);
        smallL1.put(low);
        smallL1.put(survivor);

        localTec.maybeEvict("ns", vector(4));

        assertThat(smallL1.peek("pinned")).isPresent();
        assertThat(smallL1.peek("low")).isEmpty();
        assertThat(smallL1.peek("survivor")).isPresent();
    }

    // ========================================================================
    // admitToL1
    // ========================================================================

    @Test
    void admitToL1InsertsFragmentAndReturnsTrue() {
        MemoryFragment fragment = fragment("incoming", "ns", "incoming", List.of(), 4);
        fragment.setTokenCount(8);
        fragment.setImportance(0.5);

        boolean admitted = tec.admitToL1(fragment, "test-context");

        assertThat(admitted).isTrue();
        assertThat(l1.peek("incoming")).isPresent();
    }

    @Test
    void admitToL1EnforcesCapacityByEvictingLowerValueFragments() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);

        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                0.5, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment stale = fragment("stale", "ns", "stale", List.of(), 4);
        stale.setTokenCount(10);
        stale.setImportance(0.0);
        MemoryFragment incoming = fragment("incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(12);
        incoming.setImportance(0.9);

        smallL1.put(stale, false);

        boolean admitted = localTec.admitToL1(incoming, "test-context");

        assertThat(admitted).isTrue();
        assertThat(smallL1.peek("incoming")).isPresent();
    }

    @Test
    void admitToL1ReturnsFalseWhenPinnedTokensLeaveNoEffectiveCapacity() {
        CountingCaffeineHotStore smallL1 = new CountingCaffeineHotStore(16);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);

        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                0.5, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment pinned = fragment("pinned", "ns", "pinned", List.of(), 4);
        pinned.setTokenCount(10);
        pinned.pinForMillis(60_000L);
        MemoryFragment incoming = fragment("incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(10);

        assertThat(localTec.admitToL1(pinned, "pinned-setup")).isTrue();
        smallL1.resetAllFragmentsCalls();

        boolean admitted = localTec.admitToL1(incoming, "test-context");

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(admitted).isFalse();
        assertThat(smallL1.peek("incoming")).isEmpty();
        assertThat(smallL1.allFragmentsCalls()).isZero();
        assertThat(metrics.directAttemptCount()).isEqualTo(2);
        assertThat(metrics.directCommitCount()).isEqualTo(1);
        assertThat(metrics.directRejectionCount()).isEqualTo(1);
        assertThat(metrics.directEscalationCount()).isZero();
        assertThat(metrics.optimisticAttemptCount()).isZero();
        assertThat(metrics.lockAcquisitionCount()).isEqualTo(2);
    }

    @Test
    void replacingExistingFragmentReclaimsOnlyTheGrowthDelta() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment victim = fragment("victim", "ns", "victim", List.of(), 4);
        victim.setTokenCount(10);
        victim.setImportance(0.0);
        MemoryFragment existing = fragment("same-id", "ns", "existing", List.of(), 4);
        existing.setTokenCount(10);
        existing.setImportance(1.0);
        MemoryFragment replacement = fragment("same-id", "ns", "replacement", List.of(), 4);
        replacement.setTokenCount(18);
        replacement.setImportance(1.0);

        assertThat(localTec.admitToL1(victim, "test")).isTrue();
        assertThat(localTec.admitToL1(existing, "test")).isTrue();

        assertThat(localTec.admitToL1(replacement, "test")).isTrue();

        assertThat(smallL1.peek("victim")).isEmpty();
        assertThat(smallL1.peek("same-id")).contains(replacement);
        assertThat(smallL1.currentTokenCount()).isEqualTo(18);
    }

    @Test
    void oversizedReplacementIsRejectedWithoutRemovingExistingFragment() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment existing = fragment("same-id", "ns", "existing", List.of(), 4);
        existing.setTokenCount(10);
        MemoryFragment oversized = fragment("same-id", "ns", "oversized", List.of(), 4);
        oversized.setTokenCount(30);

        assertThat(localTec.admitToL1(existing, "test")).isTrue();

        assertThat(localTec.admitToL1(oversized, "test")).isFalse();
        assertThat(smallL1.peek("same-id")).contains(existing);
        assertThat(smallL1.currentTokenCount()).isEqualTo(10);
    }

    @Test
    void replacementDoesNotEvictItsOwnReasoningChain() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment existing = fragment("same-id", "ns", "existing", List.of(), 4);
        existing.setTokenCount(10);
        existing.setReasoningChainId("protected-chain");
        MemoryFragment sibling = fragment("sibling", "ns", "sibling", List.of(), 4);
        sibling.setTokenCount(10);
        sibling.setReasoningChainId("protected-chain");
        MemoryFragment replacement = fragment("same-id", "ns", "replacement", List.of(), 4);
        replacement.setTokenCount(18);
        replacement.setReasoningChainId("protected-chain");

        assertThat(localTec.admitToL1(existing, "test")).isTrue();
        assertThat(localTec.admitToL1(sibling, "test")).isTrue();

        assertThat(localTec.admitToL1(replacement, "test")).isFalse();
        assertThat(smallL1.peek(existing.getId())).contains(existing);
        assertThat(smallL1.peek(sibling.getId())).contains(sibling);
        assertThat(smallL1.currentTokenCount()).isEqualTo(20);
    }

    @Test
    void concurrentAdmissionsAndTierRebuildsKeepCapacityAccountingExact() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(64);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    MemoryFragment incoming = fragment(
                            "concurrent-" + index, "ns", "payload-" + index, List.of(), 4);
                    incoming.setTokenCount(4);
                    localTec.admitToL1(incoming, "concurrent-test");
                    return null;
                }));
            }
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 50; iteration++) {
                        localTec.rebalanceTierIndexes();
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        long actualTokens = smallL1.allFragments().stream()
                .mapToLong(MemoryFragment::getTokenCount)
                .sum();
        assertThat(smallL1.currentTokenCount()).isEqualTo(actualTokens);
        assertThat(actualTokens).isLessThanOrEqualTo(smallL1.maxTokenCapacity());
    }

    @Test
    void noReclaimAdmissionsUseOneLockAndSkipOptimisticPlanning() throws Exception {
        int parallelism = 8;
        CountingCaffeineHotStore smallL1 = new CountingCaffeineHotStore(64);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);
        smallL1.resetAllFragmentsCalls();

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> admissions = new ArrayList<>();
        try {
            for (int index = 0; index < parallelism; index++) {
                int fragmentIndex = index;
                admissions.add(executor.submit(() -> {
                    start.await();
                    MemoryFragment incoming = fragment(
                            "fast-commit-" + fragmentIndex,
                            "ns",
                            "payload-" + fragmentIndex,
                            List.of(),
                            4);
                    incoming.setTokenCount(4);
                    return localTec.admitToL1(incoming, "fast-commit-test");
                }));
            }
            start.countDown();
            for (Future<Boolean> admission : admissions) {
                assertThat(admission.get(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(smallL1.allFragmentsCalls()).isZero();
        assertThat(smallL1.namespaceGetAllCalls()).isZero();
        assertThat(smallL1.getAll("ns")).hasSize(parallelism);
        assertThat(smallL1.currentTokenCount()).isEqualTo(32);
        assertThat(metrics.directAttemptCount()).isEqualTo(parallelism);
        assertThat(metrics.directCommitCount()).isEqualTo(parallelism);
        assertThat(metrics.directEscalationCount()).isZero();
        assertThat(metrics.directRejectionCount()).isZero();
        assertThat(metrics.optimisticAttemptCount()).isZero();
        assertThat(metrics.optimisticCommitCount()).isZero();
        assertThat(metrics.optimisticConflictCount()).isZero();
        assertThat(metrics.fallbackCount()).isZero();
        assertThat(metrics.lockAcquisitionCount()).isEqualTo(parallelism);
    }

    @Test
    void quotaOverflowEscalatesToOptimisticReclaim() {
        CountingCaffeineHotStore smallL1 = new CountingCaffeineHotStore(100);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(0.1, 0.1, 10),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment first = fragment("quota-direct", "ns", "first", List.of(), 4);
        first.setTokenCount(6);
        MemoryFragment second = fragment("quota-escalated", "ns", "second", List.of(), 4);
        second.setTokenCount(6);

        assertThat(localTec.admitToL1(first, "quota-direct-test")).isTrue();
        assertThat(localTec.admitToL1(second, "quota-escalation-test")).isTrue();

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(smallL1.allFragmentsCalls()).isEqualTo(1);
        assertThat(smallL1.getAll("ns")).hasSize(1);
        assertThat(smallL1.namespaceTokenCount("ns")).isEqualTo(6);
        assertThat(metrics.directAttemptCount()).isEqualTo(2);
        assertThat(metrics.directCommitCount()).isEqualTo(1);
        assertThat(metrics.directEscalationCount()).isEqualTo(1);
        assertThat(metrics.optimisticAttemptCount()).isEqualTo(1);
        assertThat(metrics.optimisticCommitCount()).isEqualTo(1);
        assertThat(metrics.optimisticConflictCount()).isZero();
        assertThat(metrics.fallbackCount()).isZero();
        assertThat(metrics.planningGateWaitCount()).isEqualTo(1);
        assertThat(metrics.lockAcquisitionCount()).isEqualTo(5);
    }

    @Test
    void sameNamespaceReclaimPlanningIsSerialized() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        TwoThreadBlockingEvictionPolicy blockingPolicy = new TwoThreadBlockingEvictionPolicy();
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, blockingPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment firstVictim = coldFragment("gate-victim-1", "ns", 10);
        MemoryFragment secondVictim = coldFragment("gate-victim-2", "ns", 10);
        smallL1.put(firstVictim, false);
        smallL1.put(secondVictim, false);
        localTec.rebalanceTierIndexes();

        MemoryFragment firstIncoming = hotFragment("gate-incoming-1", "ns", 10);
        MemoryFragment secondIncoming = hotFragment("gate-incoming-2", "ns", 10);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first =
                    executor.submit(() -> localTec.admitToL1(firstIncoming, "gate-first"));
            assertThat(blockingPolicy.firstPlanningStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second =
                    executor.submit(() -> localTec.admitToL1(secondIncoming, "gate-second"));
            assertThat(blockingPolicy.secondPlanningStarted.await(300, TimeUnit.MILLISECONDS))
                    .isFalse();

            blockingPolicy.releaseFirstPlanning.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            blockingPolicy.releaseFirstPlanning.countDown();
            executor.shutdownNow();
        }

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(smallL1.getAll("ns"))
                .extracting(MemoryFragment::getId)
                .containsExactlyInAnyOrder(firstIncoming.getId(), secondIncoming.getId());
        assertThat(metrics.planningGateWaitCount()).isEqualTo(2);
        assertThat(metrics.planningGateWaitNanosMax()).isPositive();
        assertThat(metrics.optimisticConflictCount()).isZero();
        assertThat(metrics.fallbackCount()).isZero();
    }

    @Test
    void differentNamespaceReclaimPlanningRemainsConcurrent() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        ConcurrentPlanningEvictionPolicy blockingPolicy = new ConcurrentPlanningEvictionPolicy();
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, blockingPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        smallL1.put(coldFragment("parallel-victim-a", "ns-a", 10), false);
        smallL1.put(coldFragment("parallel-victim-b", "ns-b", 10), false);
        localTec.rebalanceTierIndexes();
        MemoryFragment incomingA = hotFragment("parallel-incoming-a", "ns-a", 10);
        MemoryFragment incomingB = hotFragment("parallel-incoming-b", "ns-b", 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> admissionA =
                    executor.submit(() -> localTec.admitToL1(incomingA, "parallel-a"));
            Future<Boolean> admissionB =
                    executor.submit(() -> localTec.admitToL1(incomingB, "parallel-b"));
            assertThat(blockingPolicy.bothPlanningStarted.await(2, TimeUnit.SECONDS)).isTrue();

            blockingPolicy.releasePlanning.countDown();
            assertThat(admissionA.get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(admissionB.get(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            blockingPolicy.releasePlanning.countDown();
            executor.shutdownNow();
        }

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(smallL1.peek(incomingA.getId())).isPresent();
        assertThat(smallL1.peek(incomingB.getId())).isPresent();
        assertThat(blockingPolicy.maxConcurrentPlanning.get()).isEqualTo(2);
        assertThat(metrics.planningGateWaitCount()).isEqualTo(2);
        assertThat(metrics.optimisticConflictCount()).isZero();
        assertThat(metrics.fallbackCount()).isZero();
    }

    @Test
    void optimisticAdmissionPlansOutsideTheAdmissionLock() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        BlockingSemanticEvictionPolicy blockingPolicy = new BlockingSemanticEvictionPolicy();
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, blockingPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment victim = fragment("planning-victim", "ns", "victim", List.of(), 4);
        victim.setTokenCount(10);
        smallL1.put(victim, false);
        MemoryFragment incoming = fragment("planning-incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(12);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> admission =
                    executor.submit(() -> localTec.admitToL1(incoming, "optimistic-test"));
            assertThat(blockingPolicy.planningStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> removal = executor.submit(() -> localTec.removeFromL1(victim.getId()));
            assertThat(removal.get(1, TimeUnit.SECONDS)).isTrue();

            blockingPolicy.releasePlanning.countDown();
            assertThat(admission.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            blockingPolicy.releasePlanning.countDown();
            executor.shutdownNow();
        }

        assertThat(smallL1.peek(incoming.getId())).isPresent();
        assertThat(registry.get("vortex.hmc.admission.optimistic.conflict.count").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("vortex.hmc.admission.planning.max.ms").gauge().value())
                .isPositive();
        assertThat(registry.get("vortex.hmc.admission.lock.hold.total.ms").gauge().value())
                .isPositive();
    }

    @Test
    void detailedSnapshotFreezesResidentEmbeddingsOutsideAdmissionLock() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(10);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        BlockingSnapshotFragment victim = new BlockingSnapshotFragment();
        victim.setId("snapshot-victim");
        victim.setNamespace("ns");
        victim.setContent("victim");
        victim.setEmbedding(vector(4));
        victim.setTokenCount(10);
        smallL1.put(victim, false);
        localTec.rebalanceTierIndexes();

        MemoryFragment incoming = fragment("snapshot-incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> admission =
                    executor.submit(() -> localTec.admitToL1(incoming, "snapshot-outside-lock-test"));
            assertThat(victim.snapshotStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> removal = executor.submit(() -> localTec.removeFromL1(victim.getId()));
            assertThat(removal.get(1, TimeUnit.SECONDS)).isTrue();

            victim.releaseSnapshot.countDown();
            assertThat(admission.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            victim.releaseSnapshot.countDown();
            executor.shutdownNow();
        }

        assertThat(smallL1.peek(incoming.getId())).isPresent();
    }

    @Test
    void reasoningChainEvictionUpdatesTierIndexWithLinearLiveReads() {
        int chainSize = 20;
        CountingCaffeineHotStore smallL1 = new CountingCaffeineHotStore(chainSize);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        for (int index = 0; index < chainSize; index++) {
            MemoryFragment member = fragment(
                    "batch-victim-" + index,
                    "ns",
                    "victim-" + index,
                    List.of(),
                    4);
            member.setReasoningChainId("batch-chain");
            member.setTokenCount(1);
            smallL1.put(member, false);
        }
        localTec.rebalanceTierIndexes();
        smallL1.resetAllFragmentsCalls();

        MemoryFragment incoming = fragment("batch-incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(chainSize);
        assertThat(localTec.admitToL1(incoming, "batch-chain-reclaim-test")).isTrue();

        int admissionPeekCalls = smallL1.peekCalls();
        assertThat(admissionPeekCalls).isLessThan(chainSize * 4);
        assertThat(smallL1.allFragmentsCalls()).isEqualTo(1);
        assertThat(smallL1.namespaceGetAllCalls()).isZero();
        assertThat(smallL1.currentTokenCount()).isEqualTo(chainSize);
        assertThat(smallL1.getAll("ns"))
                .extracting(MemoryFragment::getId)
                .containsExactly(incoming.getId());
    }

    @Test
    void partialReasoningChainFailureFinalizesTierIndexesAndEpoch() {
        int chainSize = 3;
        CaffeineHotStore smallL1 = new CaffeineHotStore(chainSize);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FailingPinManager fpm = new FailingPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider());
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        List<String> memberIds = new ArrayList<>();
        for (int index = 0; index < chainSize; index++) {
            MemoryFragment member = fragment(
                    "partial-failure-" + index,
                    "ns",
                    "victim-" + index,
                    List.of(),
                    4);
            member.setReasoningChainId("partial-failure-chain");
            member.setTokenCount(1);
            smallL1.put(member, false);
            memberIds.add(member.getId());
        }
        localTec.rebalanceTierIndexes();
        long epochBefore = admissionEpoch(localTec);
        fpm.failOnRemoval(2);

        MemoryFragment incoming = fragment("partial-failure-incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(chainSize);

        assertThatThrownBy(() -> localTec.admitToL1(incoming, "partial-failure-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected pin-index failure");

        List<String> removedIds = memberIds.stream()
                .filter(id -> smallL1.peek(id).isEmpty())
                .toList();
        assertThat(removedIds).hasSize(2);
        assertThat(admissionEpoch(localTec)).isGreaterThan(epochBefore);
        assertThat(tierMemberships(localTec).keySet()).doesNotContainAnyElementsOf(removedIds);
    }

    @Test
    void disjointNamespaceMutationDoesNotInvalidateLocalReclaimPlan() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        BlockingSemanticEvictionPolicy blockingPolicy = new BlockingSemanticEvictionPolicy();
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, blockingPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment victim = fragment("scoped-victim", "ns-a", "victim", List.of(), 4);
        victim.setTokenCount(10);
        MemoryFragment other = fragment("scoped-other", "ns-b", "other", List.of(), 4);
        other.setTokenCount(10);
        smallL1.put(victim, false);
        smallL1.put(other, false);
        localTec.rebalanceTierIndexes();

        MemoryFragment incoming = fragment("scoped-incoming", "ns-a", "incoming", List.of(), 4);
        incoming.setTokenCount(10);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> admission =
                    executor.submit(() -> localTec.admitToL1(incoming, "scoped-local-test"));
            assertThat(blockingPolicy.planningStarted.await(2, TimeUnit.SECONDS)).isTrue();

            MemoryFragment updatedOther = fragment(
                    other.getId(), other.getNamespace(), "updated-other", List.of(), 4);
            updatedOther.setTokenCount(10);
            updatedOther.setImportance(0.7);
            assertThat(localTec.admitToL1(updatedOther, "disjoint-update-test")).isTrue();

            blockingPolicy.releasePlanning.countDown();
            assertThat(admission.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            blockingPolicy.releasePlanning.countDown();
            executor.shutdownNow();
        }

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(smallL1.peek(victim.getId())).isEmpty();
        assertThat(smallL1.peek(incoming.getId())).isPresent();
        assertThat(smallL1.peek(other.getId())).isPresent();
        assertThat(smallL1.currentTokenCount()).isEqualTo(20);
        assertThat(metrics.optimisticConflictCount()).isZero();
        assertThat(metrics.fallbackCount()).isZero();
    }

    @Test
    void scopedLocalReclaimReplansWhenConcurrentAdmissionConsumesCapacity() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        BlockingSemanticEvictionPolicy blockingPolicy = new BlockingSemanticEvictionPolicy();
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, blockingPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment victim = fragment("capacity-victim", "ns-a", "victim", List.of(), 4);
        victim.setTokenCount(10);
        smallL1.put(victim, false);
        localTec.rebalanceTierIndexes();
        MemoryFragment incoming = fragment("capacity-incoming", "ns-a", "incoming", List.of(), 4);
        incoming.setTokenCount(15);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> admission =
                    executor.submit(() -> localTec.admitToL1(incoming, "capacity-revalidation-test"));
            assertThat(blockingPolicy.planningStarted.await(2, TimeUnit.SECONDS)).isTrue();

            MemoryFragment concurrent = fragment(
                    "capacity-concurrent", "ns-b", "concurrent", List.of(), 4);
            concurrent.setTokenCount(10);
            assertThat(localTec.admitToL1(concurrent, "capacity-concurrent-test")).isTrue();

            blockingPolicy.releasePlanning.countDown();
            assertThat(admission.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            blockingPolicy.releasePlanning.countDown();
            executor.shutdownNow();
        }

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(smallL1.peek(incoming.getId())).isPresent();
        assertThat(smallL1.currentTokenCount()).isLessThanOrEqualTo(smallL1.maxTokenCapacity());
        assertThat(metrics.optimisticConflictCount()).isEqualTo(1);
        assertThat(metrics.fallbackCount()).isZero();
    }

    @Test
    void scopedReasoningChainPlanReplansWhenChainMembershipChanges() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        BlockingSemanticEvictionPolicy blockingPolicy = new BlockingSemanticEvictionPolicy();
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, blockingPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        for (int index = 0; index < 2; index++) {
            MemoryFragment member = fragment(
                    "chain-scope-" + index, "ns", "victim-" + index, List.of(), 4);
            member.setReasoningChainId("chain-scope");
            member.setTokenCount(5);
            smallL1.put(member, false);
        }
        localTec.rebalanceTierIndexes();
        MemoryFragment incoming = fragment("chain-scope-incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(15);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> admission =
                    executor.submit(() -> localTec.admitToL1(incoming, "chain-scope-test"));
            assertThat(blockingPolicy.planningStarted.await(2, TimeUnit.SECONDS)).isTrue();

            MemoryFragment addedMember = fragment(
                    "chain-scope-added", "ns", "added", List.of(), 4);
            addedMember.setReasoningChainId("chain-scope");
            addedMember.setTokenCount(1);
            smallL1.put(addedMember, false);
            localTec.rebalanceTierIndexes();

            blockingPolicy.releasePlanning.countDown();
            assertThat(admission.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            blockingPolicy.releasePlanning.countDown();
            executor.shutdownNow();
        }

        MemorySloTracker.AdmissionMetricsSnapshot metrics = tracker.admissionMetricsSnapshot();
        assertThat(smallL1.getAll("ns"))
                .extracting(MemoryFragment::getId)
                .containsExactly(incoming.getId());
        assertThat(metrics.optimisticConflictCount()).isEqualTo(1);
        assertThat(metrics.fallbackCount()).isZero();
    }

    @Test
    void repeatedRelevantVictimChangesFallBackToLockedAdmission() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(10);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySloTracker tracker = new MemorySloTracker(registry);
        tracker.bind();
        RepeatedBlockingEvictionPolicy blockingPolicy = new RepeatedBlockingEvictionPolicy(2);
        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, pm, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, blockingPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                new EvictionDecisionLogger(tracker), regretTracker, tracker, pm, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment victim = fragment("fallback-victim", "ns", "victim", List.of(), 4);
        victim.setTokenCount(10);
        smallL1.put(victim, false);
        MemoryFragment incoming = fragment("fallback-incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(10);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> admission =
                    executor.submit(() -> localTec.admitToL1(incoming, "fallback-test"));
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThat(blockingPolicy.planningStarted[attempt].await(2, TimeUnit.SECONDS)).isTrue();
                MemoryFragment changedVictim =
                        fragment(victim.getId(), "ns", "victim-" + attempt, List.of(), 4);
                changedVictim.setTokenCount(10);
                changedVictim.setImportance(attempt + 0.25);
                smallL1.put(changedVictim, false);
                localTec.rebalanceTierIndexes();
                blockingPolicy.releasePlanning[attempt].countDown();
            }
            assertThat(admission.get(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            blockingPolicy.releaseAll();
            executor.shutdownNow();
        }

        assertThat(smallL1.peek(victim.getId())).isEmpty();
        assertThat(smallL1.peek(incoming.getId())).isPresent();
        assertThat(registry.get("vortex.hmc.admission.optimistic.conflict.count").gauge().value())
                .isEqualTo(2.0);
        assertThat(registry.get("vortex.hmc.admission.fallback.count").gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void evictionPersistenceDoesNotHoldAdmissionLockOrPlanningGate() throws Exception {
        CaffeineHotStore smallL1 = new CaffeineHotStore(20);
        AtomicInteger persistenceCalls = new AtomicInteger();
        CountDownLatch firstPersistenceStarted = new CountDownLatch(1);
        CountDownLatch secondPersistenceStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPersistence = new CountDownLatch(1);
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                Files.createTempFile("vortex-blocking-persistence", ".jsonl"),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        FileBackedProcessedTaskStore processedTaskStore = new FileBackedProcessedTaskStore(
                Files.createTempFile("vortex-blocking-processed", ".txt"));
        FragmentPersistenceManager blockingPersistence = new FragmentPersistenceManager(
                l2, l3, queue, processedTaskStore, SLO_TRACKER, false, Runnable::run) {
            @Override
            public void persistAsync(MemoryFragment fragment, String reason) {
                if (persistenceCalls.incrementAndGet() != 1) {
                    secondPersistenceStarted.countDown();
                    return;
                }
                firstPersistenceStarted.countDown();
                try {
                    assertThat(releaseFirstPersistence.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        };

        FragmentPinManager fpm = new FragmentPinManager(
                smallL1, l2, l3, blockingPersistence, embedding, emptyProvider(), null);
        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, evictionPolicy, new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, blockingPersistence, WEIGHT_LEARNER, fpm,
                2.0, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment firstVictim = coldFragment("blocking-victim-1", "ns", 10);
        MemoryFragment secondVictim = coldFragment("blocking-victim-2", "ns", 10);
        smallL1.put(firstVictim, false);
        smallL1.put(secondVictim, false);
        localTec.rebalanceTierIndexes();
        MemoryFragment firstIncoming = hotFragment("blocking-incoming-1", "ns", 10);
        MemoryFragment secondIncoming = hotFragment("blocking-incoming-2", "ns", 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstAdmission =
                    executor.submit(() -> localTec.admitToL1(firstIncoming, "persistence-first"));
            assertThat(firstPersistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> secondAdmission =
                    executor.submit(() -> localTec.admitToL1(secondIncoming, "persistence-second"));
            assertThat(secondPersistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(secondAdmission.get(2, TimeUnit.SECONDS)).isTrue();

            releaseFirstPersistence.countDown();
            assertThat(firstAdmission.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirstPersistence.countDown();
            executor.shutdownNow();
        }

        assertThat(smallL1.getAll("ns"))
                .extracting(MemoryFragment::getId)
                .containsExactlyInAnyOrder(firstIncoming.getId(), secondIncoming.getId());
    }

    // ========================================================================
    // admitPage
    // ========================================================================

    @Test
    void admitPageAtomicallyAdmitsMultipleFragments() {
        MemoryFragment f1 = fragment("page-1", "ns", "page-1", List.of(), 4);
        f1.setTokenCount(5);
        f1.setImportance(0.3);
        MemoryFragment f2 = fragment("page-2", "ns", "page-2", List.of(), 4);
        f2.setTokenCount(5);
        f2.setImportance(0.3);

        SemanticPage page = SemanticPage.builder()
                .pageId("test-page")
                .centroid(vector(4))
                .state(PageState.FAULTING)
                .build();
        page.addFragment(f1.getId());
        page.addFragment(f2.getId());

        tec.admitPage(page, List.of(f1, f2));

        assertThat(l1.peek("page-1")).isPresent();
        assertThat(l1.peek("page-2")).isPresent();
    }

    // ========================================================================
    // rankEvictionForEvaluation
    // ========================================================================

    @Test
    void rankEvictionForEvaluationReturnsOrderedFragmentIds() {
        MemoryFragment low = fragment("low", "ns", "low", List.of(), 4);
        low.setImportance(0.0);
        low.setLastAccessTime(System.currentTimeMillis() - 600_000L);
        low.setTokenCount(10);
        MemoryFragment high = fragment("high", "ns", "high", List.of(), 4);
        high.setImportance(0.9);
        high.setLastAccessTime(System.currentTimeMillis());
        high.setTokenCount(10);

        CaffeineHotStore smallL1 = new CaffeineHotStore(50);
        smallL1.put(low, false);
        smallL1.put(high, false);
        tec.rebalanceTierIndexes();

        List<String> ranked = tec.rankEvictionForEvaluation(
                smallL1.getAll("ns"), vector(4), evictionPolicy.defaultProfile());

        assertThat(ranked).isNotEmpty();
    }

    @Test
    void rankEvictionForEvaluationReturnsEmptyForAllPinnedCandidates() {
        MemoryFragment pinned = fragment("pinned-rank", "ns", "pinned", List.of(), 4);
        pinned.setTokenCount(20);
        pinned.pinForMillis(60_000L);

        l1.put(pinned);
        tec.rebalanceTierIndexes();

        List<String> ranked = tec.rankEvictionForEvaluation(
                l1.getAll("ns"), vector(4), evictionPolicy.defaultProfile());

        assertThat(ranked).isEmpty();
    }

    // ========================================================================
    // rankTieredCandidates
    // ========================================================================

    @Test
    void rankTieredCandidatesReturnsEvictionCandidates() {
        MemoryFragment f1 = fragment("tc-1", "ns", "tc-1", List.of(), 4);
        f1.setImportance(0.2);
        f1.setLastAccessTime(System.currentTimeMillis() - 600_000L);
        f1.setTokenCount(8);
        MemoryFragment f2 = fragment("tc-2", "ns", "tc-2", List.of(), 4);
        f2.setImportance(0.1);
        f2.setLastAccessTime(System.currentTimeMillis());
        f2.setTokenCount(8);

        CaffeineHotStore smallL1 = new CaffeineHotStore(100);
        smallL1.put(f1, false);
        smallL1.put(f2, false);
        tec.rebalanceTierIndexes();

        List<SemanticEvictionPolicy.EvictionCandidate> candidates =
                tec.rankTieredCandidates(smallL1.getAll("ns"), vector(4), 64L);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).allMatch(c -> c.fragment() != null);
    }

    @Test
    void rankTieredCandidatesOrdersColdBeforeHot() {
        long now = System.currentTimeMillis();
        MemoryFragment coldLow = fragment("cold-low", "ns", "cold-low", List.of(), 4);
        coldLow.setImportance(0.0);
        coldLow.setLastAccessTime(now - 600_000L);
        coldLow.setTokenCount(10);
        MemoryFragment hotFrag = fragment("hot-tc", "ns", "hot-tc", List.of(), 4);
        hotFrag.setImportance(0.0);
        hotFrag.setLastAccessTime(now);
        hotFrag.setTokenCount(10);

        CaffeineHotStore smallL1 = new CaffeineHotStore(100);
        smallL1.put(coldLow, false);
        smallL1.put(hotFrag, false);
        tec.rebalanceTierIndexes();

        List<SemanticEvictionPolicy.EvictionCandidate> candidates =
                tec.rankTieredCandidates(smallL1.getAll("ns"), vector(4), 64L);

        assertThat(candidates).hasSize(2);
    }

    // ========================================================================
    // rebalanceTierIndexes
    // ========================================================================

    @Test
    void rebalanceTierIndexesChangesFragmentHotnessWhenItAges() {
        long now = System.currentTimeMillis();
        MemoryFragment fragment = fragment("rb-1", "ns", "rb-1", List.of(), 4);
        fragment.setLastAccessTime(now);
        fragment.setImportance(0.1);
        fragment.setTokenCount(8);

        CaffeineHotStore smallL1 = new CaffeineHotStore(100);
        smallL1.put(fragment, false);
        tec.rebalanceTierIndexes();

        assertThat(tec.isHot(fragment)).isTrue();

        fragment.setLastAccessTime(now - 600_000L);
        smallL1.put(fragment, false);
        tec.rebalanceTierIndexes();

        assertThat(tec.isHot(fragment)).isFalse();
    }

    @Test
    void removingHotChainMemberIncrementallyMovesRemainingGroupToColdTier() {
        long now = System.currentTimeMillis();
        MemoryFragment chainCold = fragment("chain-cold", "ns", "chain-cold", List.of(), 4);
        chainCold.setReasoningChainId("chain");
        chainCold.setLastAccessTime(now - 600_000L);
        chainCold.setTokenCount(10);
        MemoryFragment chainHot = fragment("chain-hot", "ns", "chain-hot", List.of(), 4);
        chainHot.setReasoningChainId("chain");
        chainHot.setLastAccessTime(now);
        chainHot.setTokenCount(10);
        MemoryFragment otherHot = fragment("other-hot", "ns", "other-hot", List.of(), 4);
        otherHot.setLastAccessTime(now);
        otherHot.setTokenCount(10);

        l1.put(chainCold, false);
        l1.put(chainHot, false);
        l1.put(otherHot, false);
        tec.rebalanceTierIndexes();

        assertThat(tec.removeFromL1(chainHot.getId())).isTrue();

        List<String> ranked = tec.rankTieredCandidates(l1.getAll("ns"), vector(4), 1L).stream()
                .map(candidate -> candidate.fragment().getId())
                .toList();
        assertThat(ranked).containsExactly(chainCold.getId());
    }

    @Test
    void replacingFragmentAcrossChainsIncrementallyRefreshesOldAndNewGroups() {
        long now = System.currentTimeMillis();
        MemoryFragment existing = fragment("moving", "ns", "existing", List.of(), 4);
        existing.setReasoningChainId("old-chain");
        existing.setLastAccessTime(now - 600_000L);
        existing.setTokenCount(10);
        MemoryFragment oldSibling = fragment("old-sibling", "ns", "old-sibling", List.of(), 4);
        oldSibling.setReasoningChainId("old-chain");
        oldSibling.setLastAccessTime(now - 600_000L);
        oldSibling.setTokenCount(10);
        MemoryFragment otherHot = fragment("replacement-hot", "ns", "replacement-hot", List.of(), 4);
        otherHot.setLastAccessTime(now);
        otherHot.setTokenCount(10);

        l1.put(existing, false);
        l1.put(oldSibling, false);
        l1.put(otherHot, false);
        tec.rebalanceTierIndexes();

        MemoryFragment replacement = fragment("moving", "ns", "replacement", List.of(), 4);
        replacement.setReasoningChainId("new-chain");
        replacement.setTokenCount(10);
        assertThat(tec.admitToL1(replacement, "chain-move-test")).isTrue();

        List<String> ranked = tec.rankTieredCandidates(l1.getAll("ns"), vector(4), 1L).stream()
                .map(candidate -> candidate.fragment().getId())
                .toList();
        assertThat(ranked).containsExactly(oldSibling.getId());
    }

    // ========================================================================
    // Quota
    // ========================================================================

    @Test
    void quotaReclaimsBorrowedCapacityWithoutClearingOtherNamespaceCoreSet() {
        CaffeineHotStore smallL1 = new CaffeineHotStore(120);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);

        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                0.85, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment coreB = fragment("b-core", "ns-b", "b-core", List.of(), 4);
        coreB.setTokenCount(20);
        coreB.setImportance(0.9);
        MemoryFragment borrowedB = fragment("b-borrowed", "ns-b", "b-borrowed", List.of(), 4);
        borrowedB.setTokenCount(20);
        borrowedB.setImportance(0.1);
        MemoryFragment a1 = fragment("a1", "ns-a", "a1", List.of(), 4);
        a1.setTokenCount(40);
        a1.setImportance(0.2);
        MemoryFragment a2 = fragment("a2", "ns-a", "a2", List.of(), 4);
        a2.setTokenCount(40);
        a2.setImportance(0.2);

        smallL1.put(coreB);
        smallL1.put(borrowedB);
        smallL1.put(a1);
        smallL1.put(a2);

        MemoryFragment incomingA = fragment("a3", "ns-a", "a3", List.of(), 4);
        incomingA.setTokenCount(30);
        incomingA.setImportance(0.2);
        localTec.admitToL1(incomingA, "test-context");

        assertThat(smallL1.peek("b-core")).isPresent();
        assertThat(smallL1.peek("a3")).isPresent();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static MemoryFragment fragment(String id, String namespace, String content, List<String> tags, int dim) {
        return MemoryFragment.builder()
                .id(id)
                .namespace(namespace)
                .content(content)
                .embedding(vector(dim))
                .tokenCount(Math.max(1, content.length()))
                .importance(0.5)
                .lastAccessTime(System.currentTimeMillis())
                .createdAt(Instant.now())
                .tags(tags)
                .build();
    }

    private static MemoryFragment coldFragment(String id, String namespace, int tokenCount) {
        MemoryFragment fragment = fragment(id, namespace, id, List.of(), 4);
        fragment.setTokenCount(tokenCount);
        fragment.setImportance(0.0);
        fragment.setLastAccessTime(System.currentTimeMillis() - 600_000L);
        return fragment;
    }

    private static MemoryFragment hotFragment(String id, String namespace, int tokenCount) {
        MemoryFragment fragment = fragment(id, namespace, id, List.of(), 4);
        fragment.setTokenCount(tokenCount);
        fragment.setImportance(1.0);
        return fragment;
    }

    private static float[] vector(int dim) {
        float[] v = new float[dim];
        v[0] = 1.0f;
        return v;
    }

    private static ObjectProvider<EmbeddingService> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public EmbeddingService getObject(Object... args) { return null; }
            @Override
            public EmbeddingService getIfAvailable() { return null; }
            @Override
            public EmbeddingService getIfUnique() { return null; }
            @Override
            public EmbeddingService getObject() { return null; }
            @Override
            public Iterator<EmbeddingService> iterator() { return Collections.emptyIterator(); }
        };
    }

    private FragmentPersistenceManager createPersistenceManager() {
        return createPersistenceManager(l2, l3);
    }

    private static FragmentPersistenceManager createPersistenceManager(L2WarmStore l2, L3ColdStore l3) {
        try {
            Path queueFile = Files.createTempFile("vortex-tec-test-dlq", ".jsonl");
            Path processedFile = Files.createTempFile("vortex-tec-test-processed", ".txt");
            FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                    queueFile,
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
            FileBackedProcessedTaskStore processedTaskStore = new FileBackedProcessedTaskStore(processedFile);
            return new FragmentPersistenceManager(
                    l2, l3, queue, processedTaskStore, new MemorySloTracker(new SimpleMeterRegistry()), false,
                    Runnable::run);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static class FixedEmbeddingService implements EmbeddingService {
        private final int dimension;

        private FixedEmbeddingService(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public float[] embed(String text) { return vector(dimension); }

        @Override
        public int dimension() { return dimension; }
    }

    private static class BlockingSemanticEvictionPolicy extends SemanticEvictionPolicy {
        private final AtomicBoolean blockNextRanking = new AtomicBoolean(true);
        private final CountDownLatch planningStarted = new CountDownLatch(1);
        private final CountDownLatch releasePlanning = new CountDownLatch(1);

        private BlockingSemanticEvictionPolicy() {
            super(0.3, 0.5, 0.2);
        }

        @Override
        public List<EvictionCandidate> rankCandidates(
                Collection<MemoryFragment> candidates,
                float[] queryEmbedding,
                AdaptiveWeightProfile profile) {
            if (blockNextRanking.compareAndSet(true, false)) {
                planningStarted.countDown();
                await(releasePlanning);
            }
            return super.rankCandidates(candidates, queryEmbedding, profile);
        }
    }

    private static class TwoThreadBlockingEvictionPolicy extends SemanticEvictionPolicy {
        private final AtomicBoolean blockFirstPlanning = new AtomicBoolean(true);
        private final CountDownLatch firstPlanningStarted = new CountDownLatch(1);
        private final CountDownLatch secondPlanningStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstPlanning = new CountDownLatch(1);
        private volatile Thread firstPlanningThread;

        private TwoThreadBlockingEvictionPolicy() {
            super(0.3, 0.5, 0.2);
        }

        @Override
        public List<EvictionCandidate> rankCandidates(
                Collection<MemoryFragment> candidates,
                float[] queryEmbedding,
                AdaptiveWeightProfile profile) {
            Thread currentThread = Thread.currentThread();
            if (blockFirstPlanning.compareAndSet(true, false)) {
                firstPlanningThread = currentThread;
                firstPlanningStarted.countDown();
                await(releaseFirstPlanning);
            } else if (currentThread != firstPlanningThread) {
                secondPlanningStarted.countDown();
            }
            return super.rankCandidates(candidates, queryEmbedding, profile);
        }
    }

    private static class ConcurrentPlanningEvictionPolicy extends SemanticEvictionPolicy {
        private final CountDownLatch bothPlanningStarted = new CountDownLatch(2);
        private final CountDownLatch releasePlanning = new CountDownLatch(1);
        private final AtomicInteger concurrentPlanning = new AtomicInteger();
        private final AtomicInteger maxConcurrentPlanning = new AtomicInteger();

        private ConcurrentPlanningEvictionPolicy() {
            super(0.3, 0.5, 0.2);
        }

        @Override
        public List<EvictionCandidate> rankCandidates(
                Collection<MemoryFragment> candidates,
                float[] queryEmbedding,
                AdaptiveWeightProfile profile) {
            int concurrent = concurrentPlanning.incrementAndGet();
            maxConcurrentPlanning.accumulateAndGet(concurrent, Math::max);
            bothPlanningStarted.countDown();
            try {
                await(bothPlanningStarted);
                await(releasePlanning);
                return super.rankCandidates(candidates, queryEmbedding, profile);
            } finally {
                concurrentPlanning.decrementAndGet();
            }
        }
    }

    private static class RepeatedBlockingEvictionPolicy extends SemanticEvictionPolicy {
        private final AtomicInteger rankingAttempt = new AtomicInteger();
        private final CountDownLatch[] planningStarted;
        private final CountDownLatch[] releasePlanning;

        private RepeatedBlockingEvictionPolicy(int blockedAttempts) {
            super(0.3, 0.5, 0.2);
            planningStarted = new CountDownLatch[blockedAttempts];
            releasePlanning = new CountDownLatch[blockedAttempts];
            for (int index = 0; index < blockedAttempts; index++) {
                planningStarted[index] = new CountDownLatch(1);
                releasePlanning[index] = new CountDownLatch(1);
            }
        }

        @Override
        public List<EvictionCandidate> rankCandidates(
                Collection<MemoryFragment> candidates,
                float[] queryEmbedding,
                AdaptiveWeightProfile profile) {
            int attempt = rankingAttempt.getAndIncrement();
            if (attempt < planningStarted.length) {
                planningStarted[attempt].countDown();
                await(releasePlanning[attempt]);
            }
            return super.rankCandidates(candidates, queryEmbedding, profile);
        }

        private void releaseAll() {
            Arrays.stream(releasePlanning).forEach(CountDownLatch::countDown);
        }
    }

    private static class CountingCaffeineHotStore extends CaffeineHotStore {
        private final AtomicInteger allFragmentsCalls = new AtomicInteger();
        private final AtomicInteger namespaceGetAllCalls = new AtomicInteger();
        private final AtomicInteger peekCalls = new AtomicInteger();

        private CountingCaffeineHotStore(long maxTokens) {
            super(maxTokens);
        }

        @Override
        public List<MemoryFragment> allFragments() {
            allFragmentsCalls.incrementAndGet();
            return super.allFragments();
        }

        @Override
        public List<MemoryFragment> getAll(String namespace) {
            namespaceGetAllCalls.incrementAndGet();
            return super.getAll(namespace);
        }

        @Override
        public Optional<MemoryFragment> peek(String id) {
            peekCalls.incrementAndGet();
            return super.peek(id);
        }

        private void resetAllFragmentsCalls() {
            allFragmentsCalls.set(0);
            namespaceGetAllCalls.set(0);
            peekCalls.set(0);
        }

        private int allFragmentsCalls() {
            return allFragmentsCalls.get();
        }

        private int namespaceGetAllCalls() {
            return namespaceGetAllCalls.get();
        }

        private int peekCalls() {
            return peekCalls.get();
        }
    }

    private static class FailingPinManager extends FragmentPinManager {
        private final AtomicInteger removals = new AtomicInteger();
        private volatile int failingRemoval = Integer.MAX_VALUE;

        private FailingPinManager(
                CaffeineHotStore l1,
                L2WarmStore l2,
                L3ColdStore l3,
                FragmentPersistenceManager persistenceManager,
                EmbeddingService embedding,
                ObjectProvider<EmbeddingService> cloudProvider) {
            super(l1, l2, l3, persistenceManager, embedding, cloudProvider, null);
        }

        private void failOnRemoval(int removal) {
            failingRemoval = removal;
        }

        @Override
        void removePinIndex(MemoryFragment fragment) {
            if (removals.incrementAndGet() == failingRemoval) {
                throw new IllegalStateException("injected pin-index failure");
            }
            super.removePinIndex(fragment);
        }
    }

    private static class BlockingSnapshotFragment extends MemoryFragment {
        private final AtomicBoolean blockNextEmbeddingRead = new AtomicBoolean(true);
        private final CountDownLatch snapshotStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSnapshot = new CountDownLatch(1);

        @Override
        public float[] getEmbedding() {
            if (blockNextEmbeddingRead.compareAndSet(true, false)) {
                snapshotStarted.countDown();
                await(releaseSnapshot);
            }
            return super.getEmbedding();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for admission test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static long admissionEpoch(TieredEvictionCoordinator coordinator) {
        return (long) readField(coordinator, "admissionEpoch");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> tierMemberships(TieredEvictionCoordinator coordinator) {
        return (Map<String, ?>) readField(coordinator, "tierGroupByFragment");
    }

    private static Object readField(TieredEvictionCoordinator coordinator, String fieldName) {
        try {
            java.lang.reflect.Field field =
                    TieredEvictionCoordinator.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(coordinator);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static class FakeL2WarmStore implements L2WarmStore {
        private final int dimension;
        private final List<MemoryFragment> searchResults = new ArrayList<>();

        private FakeL2WarmStore(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public void upsert(MemoryFragment fragment) {}

        @Override
        public List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK) {
            return searchResults.stream()
                    .filter(f -> namespace.equals(f.getNamespace()))
                    .limit(topK)
                    .toList();
        }

        @Override
        public Optional<MemoryFragment> get(String id) {
            return searchResults.stream().filter(f -> f.getId().equals(id)).findFirst();
        }

        @Override
        public void delete(String id) {}

        @Override
        public int vectorDimension() { return dimension; }
    }

    private static class FakeL3ColdStore implements L3ColdStore {
        private final Map<String, MemoryFragment> fragments = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void archiveFragment(MemoryFragment fragment) {
            fragments.put(fragment.getId(), fragment);
        }

        @Override
        public Optional<MemoryFragment> retrieveFragment(String id) {
            return Optional.ofNullable(fragments.get(id));
        }

        @Override
        public String saveCheckpoint(com.vortex.common.model.TaskState state) { return null; }

        @Override
        public com.vortex.common.model.CheckpointMetadata saveCheckpointWithMetadata(
                com.vortex.common.model.TaskState state, com.vortex.common.model.CheckpointMetadata meta) { return null; }

        @Override
        public com.vortex.common.model.CheckpointMetadata saveCheckpointBytesWithMetadata(
                byte[] data, com.vortex.common.model.CheckpointMetadata meta) { return null; }

        @Override
        public Optional<com.vortex.common.model.TaskState> loadCheckpoint(String checkpointId) { return Optional.empty(); }

        @Override
        public void deleteCheckpoint(String checkpointId) {}

        @Override
        public void putBytes(String key, byte[] data) {}

        @Override
        public byte[] getBytes(String key) { return null; }

        @Override
        public List<com.vortex.common.model.CheckpointMetadata> listCheckpointMetadata(String taskId) { return List.of(); }

        @Override
        public Set<String> listTaskIdsWithCheckpoints() { return Set.of(); }
    }
}
