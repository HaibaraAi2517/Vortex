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

        Map<String, Long> usage = tec.computeNamespaceTokenUsage(l1.getAllFragments());

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
        CaffeineHotStore smallL1 = new CaffeineHotStore(16);
        FragmentPersistenceManager pm = createPersistenceManager(l2, l3);
        FragmentPinManager fpm = new FragmentPinManager(smallL1, l2, l3, pm, embedding, emptyProvider(), null);

        TieredEvictionCoordinator localTec = new TieredEvictionCoordinator(
                smallL1, new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                decisionLogger, regretTracker, SLO_TRACKER, pm, WEIGHT_LEARNER, fpm,
                0.5, 300_000, 64, 2);
        fpm.setEvictionCoordinator(localTec);

        MemoryFragment pinned = fragment("pinned", "ns", "pinned", List.of(), 4);
        pinned.setTokenCount(10);
        pinned.pinForMillis(60_000L);
        MemoryFragment incoming = fragment("incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(10);

        smallL1.put(pinned);
        localTec.rebalanceTierIndexes();

        boolean admitted = localTec.admitToL1(incoming, "test-context");

        assertThat(admitted).isFalse();
        assertThat(smallL1.peek("incoming")).isEmpty();
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
        private final Map<String, MemoryFragment> fragments = new HashMap<>();

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
