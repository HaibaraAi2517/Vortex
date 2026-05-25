package com.vortex.kernel.hmc;

import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.paging.SemanticPagingManager;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.l1.CaffeineHotStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HierarchicalMemoryControllerTest {

    private static final MemorySloTracker TEST_SLO_TRACKER = new MemorySloTracker(new SimpleMeterRegistry());
    private static final AdaptiveWeightLearner TEST_WEIGHT_LEARNER =
            new AdaptiveWeightLearner(new ShadowEvaluationTracker(0.20, 14), 0.05, 100, 0.3, 0.5, 0.2);

    @Test
    void constructorFailsFastWhenL2DimensionDoesNotMatchActiveEmbeddingPath() {
        CaffeineHotStore l1 = new CaffeineHotStore(128);
        FakeL2WarmStore l2 = new FakeL2WarmStore(1024);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(512);
        ObjectProvider<EmbeddingService> cloudProvider = emptyProvider();

        assertThatThrownBy(() -> new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> 1, 64),
                localEmbedding,
                cloudProvider,
                emptyPagingProvider(),
                0.85
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("L2 vector dimension mismatch");
    }

    @Test
    void recallFiltersTagsAcrossL1AndL2AndDeduplicatesL2Hits() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);
        ObjectProvider<EmbeddingService> cloudProvider = emptyProvider();

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                cloudProvider,
                emptyPagingProvider(),
                0.85
        );

        MemoryFragment l1Match = fragment("l1-match", "ns", "query hit", List.of("role:user"), 4);
        MemoryFragment l1Miss = fragment("l1-miss", "ns", "query miss", List.of("role:system"), 4);
        l1.put(l1Match);
        l1.put(l1Miss);

        MemoryFragment l2MatchShell = fragment("l2-match", "ns", "l2 shell", List.of(), 4);
        l2.seedSearchResults(List.of(
                fragment("l1-match", "ns", "dup from l2", List.of(), 4),
                l2MatchShell,
                l2MatchShell,
                fragment("l2-miss", "ns", "wrong tag", List.of(), 4)
        ));

        l3.archiveFragment(fragment("l2-match", "ns", "l2 full", List.of("role:user"), 4));
        l3.archiveFragment(fragment("l2-miss", "ns", "l2 other", List.of("role:system"), 4));

        RecallResult result = hmc.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(5)
                .tokenBudget(100)
                .tags(List.of("role:user"))
                .build());

        assertThat(result.getFragments())
                .extracting(scored -> scored.getFragment().getId())
                .containsExactly("l1-match", "l2-match");
        assertThat(result.getSourceTrace()).containsExactly("L1", "L2");
    }

    @Test
    void recallRefreshesLastAccessTimeAndReinforcesImportanceForReturnedL1Fragments() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        long oldAccessTime = System.currentTimeMillis() - 86_400_000L;
        MemoryFragment fragment = fragment("l1-hit", "ns", "query hit", List.of("role:user"), 4);
        fragment.setImportance(0.5);
        fragment.setLastAccessTime(oldAccessTime);
        l1.put(fragment);
        fragment.setLastAccessTime(oldAccessTime);

        RecallResult result = hmc.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .build());

        MemoryFragment recalled = result.getFragments().getFirst().getFragment();
        assertThat(recalled.getLastAccessTime()).isGreaterThan(oldAccessTime);
        assertThat(recalled.getImportance()).isGreaterThan(0.5);
    }

    @Test
    void maybeEvictOnlyUsesCandidatesFromTheTriggeringNamespace() {
        CaffeineHotStore l1 = new CaffeineHotStore(100);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.5
        );

        MemoryFragment namespaceA = fragment("a-fragment", "ns-a", "ns-a fragment", List.of(), 4);
        namespaceA.setImportance(0.1);
        namespaceA.setTokenCount(20);
        MemoryFragment namespaceB = fragment("b-fragment", "ns-b", "ns-b fragment", List.of(), 4);
        namespaceB.setImportance(0.0);
        namespaceB.setTokenCount(70);
        l1.put(namespaceA);
        l1.put(namespaceB);

        hmc.maybeEvict("ns-a", vector(4));

        assertThat(l1.get("a-fragment")).isEmpty();
        assertThat(l1.get("b-fragment")).isPresent();
    }

    @Test
    void maybeEvictPrefersColdTierBeforeTouchingRecentlyAccessedFragments() {
        CaffeineHotStore l1 = new CaffeineHotStore(120);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.5,
                30_000L,
                60_000L,
                2,
                2
        );

        long now = System.currentTimeMillis();
        MemoryFragment coldLow = fragment("cold-low", "ns", "cold-low", List.of(), 4);
        coldLow.setTokenCount(30);
        coldLow.setImportance(0.0);
        coldLow.setLastAccessTime(now - 600_000L);
        MemoryFragment coldHigh = fragment("cold-high", "ns", "cold-high", List.of(), 4);
        coldHigh.setTokenCount(30);
        coldHigh.setImportance(0.2);
        coldHigh.setLastAccessTime(now - 500_000L);
        MemoryFragment hotLow = fragment("hot-low", "ns", "hot-low", List.of(), 4);
        hotLow.setTokenCount(30);
        hotLow.setImportance(0.0);
        hotLow.setLastAccessTime(now);
        MemoryFragment hotHigh = fragment("hot-high", "ns", "hot-high", List.of(), 4);
        hotHigh.setTokenCount(30);
        hotHigh.setImportance(0.9);
        hotHigh.setLastAccessTime(now);

        l1.put(coldLow, false);
        l1.put(coldHigh, false);
        l1.put(hotLow, false);
        l1.put(hotHigh, false);

        hmc.maybeEvict("ns", vector(4));

        assertThat(l1.peek("cold-low")).isEmpty();
        assertThat(l1.peek("cold-high")).isPresent();
        assertThat(l1.peek("hot-low")).isPresent();
        assertThat(l1.peek("hot-high")).isPresent();
        assertThat(hmc.sloSnapshot().tieredColdOnlySelections()).isGreaterThan(0);
    }

    @Test
    void maybeEvictTreatsRecentlyTouchedReasoningGroupAsSingleHotTierUnit() {
        CaffeineHotStore l1 = new CaffeineHotStore(160);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.5,
                30_000L,
                60_000L,
                1,
                2
        );

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

        l1.put(coldSolo, false);
        l1.put(chainOld, false);
        l1.put(chainFresh, false);
        l1.put(hotSolo, false);
        hmc.rebalanceTierIndexes();

        hmc.maybeEvict("ns", vector(4));

        assertThat(l1.peek("cold-solo")).isEmpty();
        assertThat(l1.peek("chain-old")).isPresent();
        assertThat(l1.peek("chain-fresh")).isPresent();
        assertThat(l1.peek("hot-solo")).isPresent();
        assertThat(hmc.sloSnapshot().tieredColdOnlySelections()).isGreaterThan(0);
    }

    @Test
    void recallFromL2WithinWindowCountsAsEvictionRegret() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);
        EvictionRegretTracker regretTracker = new EvictionRegretTracker(60_000L, System::currentTimeMillis);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                regretTracker,
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        MemoryFragment evicted = fragment("evicted", "ns", "query hit", List.of(), 4);
        regretTracker.recordEviction(evicted, "semantic");
        l2.seedSearchResults(List.of(evicted));

        hmc.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .build());

        EvictionRegretTracker.RegretSnapshot snapshot = regretTracker.snapshot();
        assertThat(snapshot.evictionCount()).isEqualTo(1);
        assertThat(snapshot.regretCount()).isEqualTo(1);
    }

    @Test
    void pinnedFragmentsAreNotEvictedAndReasoningChainEvictsAsGroup() {
        CaffeineHotStore l1 = new CaffeineHotStore(100);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.5
        );

        MemoryFragment pinned = fragment("pinned", "ns", "pinned", List.of(), 4);
        pinned.setTokenCount(20);
        pinned.pinForMillis(60_000L);
        MemoryFragment chainA = fragment("chain-a", "ns", "chain-a", List.of(), 4);
        chainA.setTokenCount(20);
        chainA.setReasoningChainId("chain-1");
        chainA.setImportance(0.1);
        MemoryFragment chainB = fragment("chain-b", "ns", "chain-b", List.of(), 4);
        chainB.setTokenCount(20);
        chainB.setReasoningChainId("chain-1");
        chainB.setImportance(0.1);
        MemoryFragment survivor = fragment("survivor", "ns", "survivor", List.of(), 4);
        survivor.setTokenCount(20);
        survivor.setImportance(0.9);

        l1.put(pinned);
        l1.put(chainA);
        l1.put(chainB);
        l1.put(survivor);

        hmc.maybeEvict("ns", vector(4));

        assertThat(l1.get("pinned")).isPresent();
        assertThat(l1.get("chain-a")).isEmpty();
        assertThat(l1.get("chain-b")).isEmpty();
        assertThat(l1.get("survivor")).isPresent();
    }

    @Test
    void quotaReclaimsBorrowedCapacityWithoutClearingOtherNamespaceCoreSet() {
        CaffeineHotStore l1 = new CaffeineHotStore(120);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(0.25, 0.15, 20),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95
        );

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

        l1.put(coreB);
        l1.put(borrowedB);
        l1.put(a1);
        l1.put(a2);

        MemoryFragment incomingA = fragment("a3", "ns-a", "a3", List.of(), 4);
        incomingA.setTokenCount(30);
        incomingA.setImportance(0.2);
        hmc.storeFragment(incomingA);

        assertThat(l1.get("b-core")).isPresent();
        assertThat(l1.get("a3")).isPresent();
    }

    @Test
    void pinAndUnpinFragmentWorkForArchivedFragments() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        MemoryFragment archived = fragment("archived", "ns", "archived", List.of("role:user"), 4);
        l3.archiveFragment(archived);

        Optional<MemoryFragment> pinned = hmc.pinFragment("archived", 60_000L);
        Long pinnedUntil = pinned.map(MemoryFragment::getPinnedUntil).orElse(null);
        Optional<MemoryFragment> unpinned = hmc.unpinFragment("archived");

        assertThat(pinned).isPresent();
        assertThat(pinnedUntil).isNotNull().isGreaterThan(System.currentTimeMillis());
        assertThat(unpinned).isPresent();
        assertThat(unpinned.get().getPinnedUntil()).isNull();
    }

    @Test
    void clearExpiredPinsReleasesExpiredPinStateFromL1() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        MemoryFragment expired = fragment("expired", "ns", "expired", List.of(), 4);
        hmc.storeFragment(expired);
        hmc.pinFragment("expired", 10L);
        try {
            Thread.sleep(20L);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }

        hmc.clearExpiredPins();

        MemoryFragment refreshed = l1.getAll("ns").stream()
                .filter(fragment -> "expired".equals(fragment.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(refreshed.getPinnedUntil()).isNull();
        assertThat(refreshed.isPinned()).isFalse();
    }

    @Test
    void pinAndUnpinDoNotRefreshLastAccessTime() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        MemoryFragment fragment = fragment("pin-recency", "ns", "pin-recency", List.of(), 4);
        fragment.setLastAccessTime(System.currentTimeMillis() - 60_000L);
        l1.put(fragment, false);
        long originalLastAccessTime = fragment.getLastAccessTime();

        hmc.pinFragment("pin-recency", 60_000L);
        long afterPin = l1.getAll("ns").stream()
                .filter(candidate -> "pin-recency".equals(candidate.getId()))
                .findFirst()
                .orElseThrow()
                .getLastAccessTime();
        hmc.unpinFragment("pin-recency");
        long afterUnpin = l1.getAll("ns").stream()
                .filter(candidate -> "pin-recency".equals(candidate.getId()))
                .findFirst()
                .orElseThrow()
                .getLastAccessTime();

        assertThat(afterPin).isEqualTo(originalLastAccessTime);
        assertThat(afterUnpin).isEqualTo(originalLastAccessTime);
    }

    @Test
    void recallFromL2RestoresArchivedTagsAndReasoningMetadata() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        MemoryFragment l2Shell = fragment("l2-rich", "ns", "shell", List.of(), 4);
        l2.seedSearchResults(List.of(l2Shell));

        MemoryFragment archived = fragment("l2-rich", "ns", "full", List.of("role:user"), 4);
        archived.setReasoningChainId("chain-1");
        archived.pinForMillis(60_000L);
        l3.archiveFragment(archived);

        RecallResult result = hmc.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(1)
                .tokenBudget(100)
                .tags(List.of("role:user"))
                .build());

        MemoryFragment recalled = result.getFragments().getFirst().getFragment();
        assertThat(recalled.getTags()).containsExactly("role:user");
        assertThat(recalled.getReasoningChainId()).isEqualTo("chain-1");
        assertThat(recalled.isPinned()).isTrue();
    }

    @Test
    void recallProducesSessionAndFeedbackUpdatesLearningSnapshot() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(new ShadowEvaluationTracker(0.20, 14), 0.05, 100, 0.3, 0.5, 0.2);
        MemorySloTracker sloTracker = new MemorySloTracker(new SimpleMeterRegistry());

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                learner,
                new EvictionDecisionLogger(sloTracker),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                sloTracker,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        MemoryFragment fragment = fragment("learn-me", "ns", "query hit", List.of(), 4);
        l1.put(fragment);

        RecallResult result = hmc.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .scenario(MemoryScenario.CODING)
                .topK(1)
                .tokenBudget(100)
                .build());

        assertThat(result.getRecallSessionId()).isNotBlank();
        hmc.recordFeedback(MemoryFeedbackRequest.builder()
                .recallSessionId(result.getRecallSessionId())
                .usedFragmentIds(List.of("learn-me"))
                .answerAccepted(true)
                .build());

        AdaptiveWeightLearner.LearningSnapshot snapshot = hmc.learningSnapshot(MemoryScenario.CODING);
        assertThat(snapshot.active().getUpdateCount()).isGreaterThan(0);
        assertThat(snapshot.shadowEvaluation().sampleCount()).isGreaterThan(0);
    }

    @Test
    void recallEvaluatesShadowAndBaselineAgainstIndependentRankingOrders() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(new ShadowEvaluationTracker(0.20, 14), 0.05, 100, 0.3, 0.5, 0.2);
        MemorySloTracker sloTracker = new MemorySloTracker(new SimpleMeterRegistry());

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                learner,
                new EvictionDecisionLogger(sloTracker),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                sloTracker,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        long now = System.currentTimeMillis();
        MemoryFragment recent = fragment("recent", "ns", "recent", List.of(), 4);
        recent.setImportance(0.0);
        recent.setEmbedding(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        recent.setLastAccessTime(now - (2 * 86_400_000L));
        MemoryFragment important = fragment("important", "ns", "important", List.of(), 4);
        important.setImportance(1.0);
        important.setEmbedding(new float[]{0.22f, 0.9755f, 0.0f, 0.0f});
        important.setLastAccessTime(now);
        l1.put(recent, false);
        l1.put(important, false);

        RecallResult result = hmc.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .scenario(MemoryScenario.CHAT)
                .topK(2)
                .tokenBudget(100)
                .build());

        RecallSessionRecord session = learner.peekRecallSession(result.getRecallSessionId());
        assertThat(session).isNotNull();
        assertThat(session.getActiveArmIndex()).isNotNull();
        assertThat(session.getShadowArmIndex()).isNotNull();
        assertThat(session.getActiveSelectionProbability()).isGreaterThan(0.0);
        assertThat(session.getShadowSelectionProbability()).isGreaterThan(0.0);
        assertThat(session.getRankedFragmentIds()).containsExactly("recent", "important");
        assertThat(session.getShadowRankedFragmentIds()).containsExactlyInAnyOrder("important", "recent");
        assertThat(session.getShadowRankedFragmentIds()).isNotEqualTo(session.getRankedFragmentIds());
        assertThat(session.getBaselineRankedFragmentIds()).containsExactlyInAnyOrder("recent", "important");
        assertThat(session.getActiveEvictionRankedFragmentIds()).containsExactlyInAnyOrder("recent", "important");
        assertThat(session.getShadowEvictionRankedFragmentIds()).containsExactlyInAnyOrder("important", "recent");

        hmc.recordFeedback(MemoryFeedbackRequest.builder()
                .recallSessionId(result.getRecallSessionId())
                .usedFragmentIds(List.of("important"))
                .answerAccepted(true)
                .build());

        AdaptiveWeightLearner.LearningSnapshot snapshot = hmc.learningSnapshot(MemoryScenario.CHAT);
        assertThat(snapshot.shadowEvaluation().sampleCount()).isEqualTo(1);
        assertThat(snapshot.shadowEvaluation().baselineAverageNdcg()).isGreaterThan(0.0);
        assertThat(snapshot.shadowEvaluation().shadowAverageNdcg()).isGreaterThan(snapshot.shadowEvaluation().activeAverageNdcg());
    }

    @Test
    void pinnedFragmentsSurviveL1OverflowBecauseCapacityIsHmcControlled() {
        CaffeineHotStore l1 = new CaffeineHotStore(12);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(new ShadowEvaluationTracker(0.20, 14), 0.05, 100, 0.3, 0.5, 0.2);
        MemorySloTracker sloTracker = new MemorySloTracker(new SimpleMeterRegistry());

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                learner,
                new EvictionDecisionLogger(sloTracker),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                sloTracker,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95
        );

        MemoryFragment pinned = fragment("pinned-overflow", "ns", "pin-a", List.of(), 4);
        pinned.setTokenCount(8);
        pinned.pinForMillis(60_000L);
        MemoryFragment evictable = fragment("evictable-overflow", "ns", "spill", List.of(), 4);
        evictable.setTokenCount(8);
        evictable.setImportance(0.0);

        hmc.storeFragment(pinned);
        hmc.storeFragment(evictable);

        assertThat(l1.peek("pinned-overflow")).isPresent();
        assertThat(l1.peek("evictable-overflow")).isEmpty();
        assertThat(l1.currentTokenCount()).isLessThanOrEqualTo(l1.maxTokenCapacity());
        assertThat(l3.retrieveFragment("evictable-overflow")).isPresent();
    }

    @Test
    void nonPinnedVictimIsEvictedToAdmitHigherValueFragmentWithinEffectiveCapacity() {
        CaffeineHotStore l1 = new CaffeineHotStore(12);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(new ShadowEvaluationTracker(0.20, 14), 0.05, 100, 0.3, 0.5, 0.2);
        MemorySloTracker sloTracker = new MemorySloTracker(new SimpleMeterRegistry());

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                learner,
                new EvictionDecisionLogger(sloTracker),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                sloTracker,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95
        );

        MemoryFragment pinned = fragment("pinned-core", "ns", "pin-a", List.of(), 4);
        pinned.setTokenCount(4);
        pinned.pinForMillis(60_000L);
        MemoryFragment stale = fragment("stale", "ns", "stale", List.of(), 4);
        stale.setTokenCount(8);
        stale.setImportance(0.0);
        MemoryFragment incoming = fragment("incoming", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(8);
        incoming.setImportance(0.9);

        hmc.storeFragment(pinned);
        hmc.storeFragment(stale);
        hmc.storeFragment(incoming);

        assertThat(l1.peek("pinned-core")).isPresent();
        assertThat(l1.peek("stale")).isEmpty();
        assertThat(l1.peek("incoming")).isPresent();
        assertThat(l3.retrieveFragment("stale")).isPresent();
    }

    @Test
    void admissionReclaimPrefersColdVictimsBeforeExpandingIntoHotTier() {
        CaffeineHotStore l1 = new CaffeineHotStore(12);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95,
                30_000L,
                60_000L,
                1,
                2
        );

        long now = System.currentTimeMillis();
        MemoryFragment coldVictim = fragment("cold-victim", "ns", "cold", List.of(), 4);
        coldVictim.setTokenCount(4);
        coldVictim.setImportance(0.0);
        coldVictim.setLastAccessTime(now - 600_000L);
        MemoryFragment hotVictim = fragment("hot-victim", "ns", "hot", List.of(), 4);
        hotVictim.setTokenCount(4);
        hotVictim.setImportance(0.0);
        hotVictim.setLastAccessTime(now);
        MemoryFragment pinned = fragment("pinned-admission", "ns", "pin", List.of(), 4);
        pinned.setTokenCount(4);
        pinned.pinForMillis(60_000L);
        MemoryFragment incoming = fragment("incoming-tiered", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(4);
        incoming.setImportance(0.9);

        l1.put(coldVictim, false);
        l1.put(hotVictim, false);
        hmc.storeFragment(pinned);
        hmc.storeFragment(incoming);

        assertThat(l1.peek("cold-victim")).isEmpty();
        assertThat(l1.peek("hot-victim")).isPresent();
        assertThat(l1.peek("incoming-tiered")).isPresent();
    }

    @Test
    void tieredExpansionMetricIncrementsWhenColdTierCannotCoverGap() {
        CaffeineHotStore l1 = new CaffeineHotStore(16);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95,
                30_000L,
                60_000L,
                1,
                2
        );

        long now = System.currentTimeMillis();
        MemoryFragment coldVictim = fragment("cold-expand", "ns", "cold", List.of(), 4);
        coldVictim.setTokenCount(2);
        coldVictim.setImportance(0.0);
        coldVictim.setLastAccessTime(now - 600_000L);
        MemoryFragment hotVictim = fragment("hot-expand", "ns", "hot", List.of(), 4);
        hotVictim.setTokenCount(6);
        hotVictim.setImportance(0.0);
        hotVictim.setLastAccessTime(now);
        MemoryFragment pinned = fragment("pinned-expand", "ns", "pin", List.of(), 4);
        pinned.setTokenCount(8);
        pinned.pinForMillis(60_000L);
        MemoryFragment incoming = fragment("incoming-expand", "ns", "incoming", List.of(), 4);
        incoming.setTokenCount(6);
        incoming.setImportance(0.9);

        l1.put(coldVictim, false);
        l1.put(hotVictim, false);
        hmc.storeFragment(pinned);
        hmc.storeFragment(incoming);

        assertThat(l1.peek("incoming-expand")).isPresent();
        assertThat(hmc.sloSnapshot().tieredExpandedSelections()).isGreaterThan(0);
    }

    @Test
    void admitPageKeepsL1WithinTokenCapacity() {
        CaffeineHotStore l1 = new CaffeineHotStore(24);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95,
                30_000L,
                60_000L,
                1,
                2
        );

        MemoryFragment residentA = fragment("resident-a", "ns", "resident-a", List.of(), 4);
        residentA.setTokenCount(10);
        residentA.setImportance(0.8);
        MemoryFragment residentB = fragment("resident-b", "ns", "resident-b", List.of(), 4);
        residentB.setTokenCount(10);
        residentB.setImportance(0.8);
        MemoryFragment pagedIn = fragment("paged-in", "ns", "paged-in", List.of(), 4);
        pagedIn.setTokenCount(10);
        pagedIn.setImportance(0.2);

        hmc.storeFragment(residentA);
        hmc.storeFragment(residentB);

        SemanticPage page = SemanticPage.builder()
                .pageId("page-capacity")
                .centroid(vector(4))
                .state(PageState.FAULTING)
                .build();
        page.addFragment(pagedIn.getId());

        hmc.admitPage(page, List.of(pagedIn));

        assertThat(l1.currentTokenCount()).isLessThanOrEqualTo(24);
        assertThat(l1.peek("paged-in")).isPresent();
        assertThat(l1.getAll("ns")).hasSize(2);
    }

    @Test
    void admitPageDoesNotEvictNewRecallWhenRefreshingResidentFragments() {
        CaffeineHotStore l1 = new CaffeineHotStore(24);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95,
                30_000L,
                60_000L,
                1,
                2
        );

        MemoryFragment medium = fragment("medium", "ns", "medium", List.of(), 4);
        medium.setTokenCount(10);
        medium.setImportance(0.9);
        MemoryFragment filler = fragment("filler", "ns", "filler", List.of(), 4);
        filler.setTokenCount(10);
        filler.setImportance(0.9);
        MemoryFragment cold = fragment("cold", "ns", "cold", List.of(), 4);
        cold.setTokenCount(10);
        cold.setImportance(0.1);

        hmc.storeFragment(medium);
        hmc.storeFragment(filler);
        hmc.admitPage(
                SemanticPage.builder().pageId("page-refresh").centroid(vector(4)).state(PageState.FAULTING).build(),
                List.of(cold, medium, filler),
                cold.getId());

        assertThat(l1.currentTokenCount()).isLessThanOrEqualTo(24);
        assertThat(l1.getAll("ns")).hasSizeLessThanOrEqualTo(2);
        assertThat(l1.getAll("ns").stream().map(MemoryFragment::getId)).contains("cold");
    }

    @Test
    void admitPageSkipsCompanionThatWouldDisplaceRecalledPrimary() {
        CaffeineHotStore l1 = new CaffeineHotStore(24);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95,
                30_000L,
                60_000L,
                1,
                2
        );

        MemoryFragment medium = fragment("medium-keep", "ns", "medium", List.of(), 4);
        medium.setTokenCount(10);
        medium.setImportance(0.9);
        MemoryFragment filler = fragment("filler-skip", "ns", "filler", List.of(), 4);
        filler.setTokenCount(10);
        filler.setImportance(0.8);
        MemoryFragment cold = fragment("cold-anchor", "ns", "cold", List.of(), 4);
        cold.setTokenCount(10);
        cold.setImportance(0.1);

        hmc.storeFragment(medium);
        hmc.storeFragment(filler);
        l1.remove(filler.getId());
        l1.put(cold, false);
        hmc.rebalanceTierIndexes();

        hmc.admitPage(
                SemanticPage.builder().pageId("page-opportunistic").centroid(vector(4)).state(PageState.FAULTING).build(),
                List.of(cold, medium, filler),
                cold.getId());

        assertThat(l1.currentTokenCount()).isLessThanOrEqualTo(24);
        assertThat(l1.peek(cold.getId())).isPresent();
        assertThat(l1.peek(medium.getId())).isPresent();
        assertThat(l1.peek(filler.getId())).isEmpty();
    }

    @Test
    void rebalanceTierIndexesMovesStaleReasoningGroupIntoColdPoolAfterTimeDrift() {
        CaffeineHotStore l1 = new CaffeineHotStore(160);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.5,
                30_000L,
                60_000L,
                1,
                2
        );

        long now = System.currentTimeMillis();
        MemoryFragment driftingA = fragment("drift-a", "ns", "drift-a", List.of(), 4);
        driftingA.setReasoningChainId("drift-group");
        driftingA.setTokenCount(20);
        driftingA.setImportance(0.0);
        driftingA.setLastAccessTime(now);
        MemoryFragment driftingB = fragment("drift-b", "ns", "drift-b", List.of(), 4);
        driftingB.setReasoningChainId("drift-group");
        driftingB.setTokenCount(20);
        driftingB.setImportance(0.0);
        driftingB.setLastAccessTime(now);
        MemoryFragment coldVictim = fragment("rebalance-cold", "ns", "rebalance-cold", List.of(), 4);
        coldVictim.setTokenCount(40);
        coldVictim.setImportance(0.0);
        coldVictim.setLastAccessTime(now - 700_000L);
        MemoryFragment survivor = fragment("rebalance-survivor", "ns", "rebalance-survivor", List.of(), 4);
        survivor.setTokenCount(40);
        survivor.setImportance(0.9);
        survivor.setLastAccessTime(now);

        l1.put(driftingA, false);
        l1.put(driftingB, false);
        l1.put(coldVictim, false);
        l1.put(survivor, false);
        hmc.rebalanceTierIndexes();

        driftingA.setLastAccessTime(now - 700_000L);
        driftingB.setLastAccessTime(now - 700_000L);
        l1.put(driftingA, false);
        l1.put(driftingB, false);

        hmc.maybeEvict("ns", vector(4));

        assertThat(l1.peek("rebalance-cold")).isEmpty();
        assertThat(l1.peek("drift-a")).isPresent();
        assertThat(l1.peek("drift-b")).isPresent();

        hmc.rebalanceTierIndexes();
        hmc.maybeEvict("ns", vector(4));

        assertThat(l1.peek("drift-a")).isEmpty();
        assertThat(l1.peek("drift-b")).isEmpty();
        assertThat(l1.peek("rebalance-survivor")).isPresent();
        assertThat(hmc.sloSnapshot().tieredColdOnlySelections()).isGreaterThan(0);
    }

    @Test
    void admissionIsRejectedWhenPinnedTokensLeaveNoEffectiveCapacity() {
        CaffeineHotStore l1 = new CaffeineHotStore(12);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.0, 0.0, 1.0),
                new NamespaceQuotaManager(1.0, 1.0, 1),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.95
        );

        MemoryFragment pinnedA = fragment("pinned-a", "ns", "aaaa", List.of(), 4);
        pinnedA.setTokenCount(6);
        pinnedA.pinForMillis(60_000L);
        MemoryFragment pinnedB = fragment("pinned-b", "ns", "bbbb", List.of(), 4);
        pinnedB.setTokenCount(6);
        pinnedB.pinForMillis(60_000L);
        MemoryFragment incoming = fragment("incoming-reject", "ns", "ccc", List.of(), 4);
        incoming.setTokenCount(2);

        hmc.storeFragment(pinnedA);
        hmc.storeFragment(pinnedB);
        hmc.storeFragment(incoming);

        assertThat(l1.peek("incoming-reject")).isEmpty();
        assertThat(l1.peek("pinned-a")).isPresent();
        assertThat(l1.peek("pinned-b")).isPresent();
        assertThat(l1.currentTokenCount()).isEqualTo(12);
    }

    @Test
    void recallFromL2UsesIncrementalRedundancyUpdatesInsteadOfFullRecomputePerHit() {
        CaffeineHotStore l1 = new CaffeineHotStore(512);
        CountingL2WarmStore l2 = new CountingL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        hmc.storeFragment(fragment("l1-1", "ns", "base-one", List.of(), 4));
        hmc.storeFragment(fragment("l1-2", "ns", "base-two", List.of(), 4));

        MemoryFragment l2a = fragment("l2-a", "ns", "hit-a", List.of(), 4);
        MemoryFragment l2b = fragment("l2-b", "ns", "hit-b", List.of(), 4);
        l2.seedSearchResults(List.of(l2a, l2b));
        l3.archiveFragment(l2a);
        l3.archiveFragment(l2b);

        hmc.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(4)
                .tokenBudget(100)
                .build());

        assertThat(l2.searchResultsServed()).isEqualTo(2);
        assertThat(l1.peek("l2-a")).isPresent();
        assertThat(l1.peek("l2-b")).isPresent();
    }

    @Test
    void incrementalRedundancyMatchesFullRecomputeWhenNoveltyDropsToZero() {
        CaffeineHotStore l1 = new CaffeineHotStore(512);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        MemoryFragment identicalL1 = fragment("l1-same", "ns", "same", List.of(), 4);
        identicalL1.setEmbedding(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        MemoryFragment orthogonalL1 = fragment("l1-other", "ns", "other", List.of(), 4);
        orthogonalL1.setEmbedding(new float[]{0.0f, 1.0f, 0.0f, 0.0f});
        hmc.storeFragment(identicalL1);
        hmc.storeFragment(orthogonalL1);

        MemoryFragment l2Candidate = fragment("l2-same", "ns", "same-hit", List.of(), 4);
        l2Candidate.setEmbedding(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        l2.seedSearchResults(List.of(l2Candidate));
        l3.archiveFragment(l2Candidate);

        RecallResult result = hmc.recall(RecallQuery.builder()
                .query("query")
                .namespace("ns")
                .topK(3)
                .tokenBudget(100)
                .build());

        RecallResult.ScoredFragment recalled = result.getFragments().stream()
                .filter(fragment -> "l2-same".equals(fragment.getFragment().getId()))
                .findFirst()
                .orElseThrow();

        List<MemoryFragment> allCandidates = List.of(
                l1.getAll("ns").stream().filter(fragment -> "l1-same".equals(fragment.getId())).findFirst().orElseThrow(),
                l1.getAll("ns").stream().filter(fragment -> "l1-other".equals(fragment.getId())).findFirst().orElseThrow(),
                recalled.getFragment());
        double expected = recalled.getFragment().describeEvictionScore(
                vector(4),
                0.3,
                0.5,
                0.2,
                allCandidates.stream()
                        .filter(other -> other != recalled.getFragment())
                        .mapToDouble(recalled.getFragment()::redundancyPenaltyAgainst)
                        .max()
                        .orElse(0.0),
                allCandidates.stream()
                        .filter(other -> other != recalled.getFragment())
                        .mapToDouble(recalled.getFragment()::noveltyBonusAgainst)
                        .min()
                        .orElse(0.0)).totalScore();

        assertThat(recalled.getScore()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1.0e-7));
    }

    @Test
    void repeatedPinUpdatesDoNotBreakExpiryCleanup() {
        CaffeineHotStore l1 = new CaffeineHotStore(256);
        FakeL2WarmStore l2 = new FakeL2WarmStore(4);
        FakeL3ColdStore l3 = new FakeL3ColdStore();
        EmbeddingService localEmbedding = new FixedEmbeddingService(4);

        HierarchicalMemoryController hmc = new HierarchicalMemoryController(
                l1,
                l2,
                l3,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                new NamespaceQuotaManager(0.25, 0.15, 16),
                TEST_WEIGHT_LEARNER,
                new EvictionDecisionLogger(TEST_SLO_TRACKER),
                new EvictionRegretTracker(3_600_000L, System::currentTimeMillis),
                TEST_SLO_TRACKER,
                persistenceManager(l2, l3),
                new SemanticTextSplitter(text -> Math.max(1, text.length()), 64),
                localEmbedding,
                emptyProvider(),
                emptyPagingProvider(),
                0.85
        );

        hmc.storeFragment(fragment("re-pin", "ns", "re-pin", List.of(), 4));
        hmc.pinFragment("re-pin", 5L);
        hmc.pinFragment("re-pin", 10L);
        hmc.pinFragment("re-pin", 15L);
        try {
            Thread.sleep(25L);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }

        hmc.clearExpiredPins();

        MemoryFragment refreshed = l1.getAll("ns").stream()
                .filter(fragment -> "re-pin".equals(fragment.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(refreshed.isPinned()).isFalse();
        assertThat(refreshed.getPinnedUntil()).isNull();
    }

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
        float[] vector = new float[dim];
        vector[0] = 1.0f;
        return vector;
    }

    private static ObjectProvider<SemanticPagingManager> emptyPagingProvider() {
        return new ObjectProvider<>() {
            @Override
            public SemanticPagingManager getObject(Object... args) {
                return null;
            }

            @Override
            public SemanticPagingManager getIfAvailable() {
                return null;
            }

            @Override
            public SemanticPagingManager getIfUnique() {
                return null;
            }

            @Override
            public SemanticPagingManager getObject() {
                return null;
            }

            @Override
            public Iterator<SemanticPagingManager> iterator() {
                return Collections.emptyIterator();
            }
        };
    }

    private static ObjectProvider<EmbeddingService> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public EmbeddingService getObject(Object... args) {
                return null;
            }

            @Override
            public EmbeddingService getIfAvailable() {
                return null;
            }

            @Override
            public EmbeddingService getIfUnique() {
                return null;
            }

            @Override
            public EmbeddingService getObject() {
                return null;
            }

            @Override
            public Iterator<EmbeddingService> iterator() {
                return Collections.emptyIterator();
            }
        };
    }

    private static FragmentPersistenceManager persistenceManager(L2WarmStore l2, L3ColdStore l3) {
        try {
            Path queueFile = Files.createTempFile("vortex-hmc-test-dlq", ".jsonl");
            Path processedFile = Files.createTempFile("vortex-hmc-test-processed", ".txt");
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

    private static final class FixedEmbeddingService implements EmbeddingService {

        private final int dimension;

        private FixedEmbeddingService(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public float[] embed(String text) {
            return vector(dimension);
        }

        @Override
        public int dimension() {
            return dimension;
        }
    }

    private static class FakeL2WarmStore implements L2WarmStore {

        private final int dimension;
        private final List<MemoryFragment> searchResults = new ArrayList<>();

        private FakeL2WarmStore(int dimension) {
            this.dimension = dimension;
        }

        protected void seedSearchResults(List<MemoryFragment> fragments) {
            searchResults.clear();
            searchResults.addAll(fragments);
        }

        @Override
        public void upsert(MemoryFragment fragment) {
        }

        @Override
        public List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK) {
            return searchResults.stream()
                    .filter(fragment -> namespace.equals(fragment.getNamespace()))
                    .limit(topK)
                    .toList();
        }

        @Override
        public Optional<MemoryFragment> get(String id) {
            return searchResults.stream().filter(fragment -> fragment.getId().equals(id)).findFirst();
        }

        @Override
        public void delete(String id) {
        }

        @Override
        public int vectorDimension() {
            return dimension;
        }
    }

    private static final class CountingL2WarmStore extends FakeL2WarmStore {
        private final AtomicInteger served = new AtomicInteger();

        private CountingL2WarmStore(int dimension) {
            super(dimension);
        }

        @Override
        public List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK) {
            List<MemoryFragment> results = super.search(queryEmbedding, namespace, topK);
            served.addAndGet(results.size());
            return results;
        }

        private int searchResultsServed() {
            return served.get();
        }
    }

    private static final class FakeL3ColdStore implements L3ColdStore {

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
        public String saveCheckpoint(TaskState state) {
            return "checkpoint";
        }

        @Override
        public Optional<TaskState> loadCheckpoint(String checkpointId) {
            return Optional.empty();
        }

        @Override
        public void deleteCheckpoint(String checkpointId) {
        }
    }
}
