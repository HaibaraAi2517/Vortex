package com.vortex.kernel.paging;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.SemanticPage;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.storage.api.L3ColdStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class PrefetchEngineTest {

    @Test
    void boundedQueueDropsIncomingTaskWhenCapacityReachedBySameOrHigherPriorityWork() {
        SemanticPageTable pageTable = new SemanticPageTable(new NoopColdStore());
        PrefetchEngine engine = new PrefetchEngine(
                pageTable,
                new PageFaultHandler(pageTable, null, null, null, new NoopEmbeddingService(), emptyProvider()),
                2,
                3,
                3,
                0.7,
                1,
                2,
                1_000L,
                1,
                false);

        assertThat(engine.submitPrefetchForTest("page-low", 2, "test-low")).isTrue();
        assertThat(engine.submitPrefetchForTest("page-mid", 5, "test-mid")).isTrue();
        assertThat(engine.submitPrefetchForTest("page-another-low", 2, "test-low-2")).isFalse();

        assertThat(engine.queuedTaskCountForTest()).isEqualTo(2);
        assertThat(engine.queuedPageIdsForTest()).containsExactly("page-mid", "page-low");
    }

    @Test
    void boundedQueueEvictsLowerPriorityTaskForHigherPriorityPrefetch() {
        SemanticPageTable pageTable = new SemanticPageTable(new NoopColdStore());
        PrefetchEngine engine = new PrefetchEngine(
                pageTable,
                new PageFaultHandler(pageTable, null, null, null, new NoopEmbeddingService(), emptyProvider()),
                2,
                3,
                3,
                0.7,
                1,
                2,
                1_000L,
                1,
                false);

        engine.submitPrefetchForTest("page-branch", 2, "branch");
        engine.submitPrefetchForTest("page-semantic", 5, "semantic");

        assertThat(engine.submitPrefetchForTest("page-dag", 10, "dag")).isTrue();

        assertThat(engine.queuedTaskCountForTest()).isEqualTo(2);
        assertThat(engine.queuedPageIdsForTest()).containsExactly("page-dag", "page-semantic");
        assertThat(engine.queuedPageIdsForTest()).doesNotContain("page-branch");
    }

    @Test
    void actualFragmentAccessCountsAsPrefetchHit() {
        SemanticPageTable pageTable = new SemanticPageTable(new NoopColdStore());
        SemanticPage page = SemanticPage.builder()
                .pageId("page-1")
                .centroid(new float[]{1.0f})
                .build();
        page.addFragment("fragment-1");
        pageTable.putPage(page);

        PrefetchEngine engine = new PrefetchEngine(
                pageTable,
                new ImmediatePageFaultHandler(pageTable),
                2,
                3,
                3,
                0.7,
                1,
                2,
                1_000L,
                1,
                false);

        engine.preloadPage("page-1").join();
        engine.recordFragmentAccess("fragment-1");

        PrefetchEngine.StrategyStats stats = engine.strategyStatsForTest("manual");
        assertThat(stats.requested()).isEqualTo(1);
        assertThat(stats.consumed()).isEqualTo(1);
        assertThat(stats.missed()).isZero();
    }

    @Test
    void expiredPrefetchCountsAsMissInsteadOfHit() throws Exception {
        SemanticPageTable pageTable = new SemanticPageTable(new NoopColdStore());
        SemanticPage page = SemanticPage.builder()
                .pageId("page-2")
                .centroid(new float[]{1.0f})
                .build();
        page.addFragment("fragment-2");
        pageTable.putPage(page);

        PrefetchEngine engine = new PrefetchEngine(
                pageTable,
                new ImmediatePageFaultHandler(pageTable),
                2,
                3,
                3,
                0.7,
                1,
                2,
                10L,
                1,
                false);

        engine.preloadPage("page-2").join();
        Thread.sleep(20L);
        engine.getStats();

        PrefetchEngine.StrategyStats stats = engine.strategyStatsForTest("manual");
        assertThat(stats.requested()).isEqualTo(1);
        assertThat(stats.consumed()).isZero();
        assertThat(stats.missed()).isEqualTo(1);
    }

    @Test
    void sustainedMissesReduceSemanticPrefetchBudget() throws Exception {
        SemanticPageTable pageTable = new SemanticPageTable(new NoopColdStore());
        PrefetchEngine engine = new PrefetchEngine(
                pageTable,
                new ImmediatePageFaultHandler(pageTable),
                2,
                3,
                4,
                0.7,
                1,
                32,
                15L,
                4,
                true);

        for (int i = 0; i < 8; i++) {
            assertThat(engine.submitPrefetchForTest("semantic-page-" + i, 5, "semantic-nbhd")).isTrue();
        }

        waitUntil(() -> engine.strategyStatsForTest("semantic-nbhd").requested() >= 8, 1_000L);
        Thread.sleep(30L);
        engine.getStats();

        PrefetchEngine.StrategySnapshot snapshot = engine.strategySnapshot("semantic-nbhd");
        assertThat(snapshot.requested()).isGreaterThanOrEqualTo(8);
        assertThat(snapshot.hitRate()).isEqualTo(0.0);
        assertThat(snapshot.effectiveBudget()).isEqualTo(1);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Condition was not met within timeout " + timeoutMs + "ms");
    }

    private static ObjectProvider<com.vortex.kernel.hmc.HierarchicalMemoryController> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public com.vortex.kernel.hmc.HierarchicalMemoryController getObject(Object... args) {
                return null;
            }

            @Override
            public com.vortex.kernel.hmc.HierarchicalMemoryController getIfAvailable() {
                return null;
            }

            @Override
            public com.vortex.kernel.hmc.HierarchicalMemoryController getIfUnique() {
                return null;
            }

            @Override
            public com.vortex.kernel.hmc.HierarchicalMemoryController getObject() {
                return null;
            }
        };
    }

    private static final class NoopEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{1.0f};
        }

        @Override
        public int dimension() {
            return 1;
        }
    }

    private static final class NoopColdStore implements L3ColdStore {
        @Override
        public void archiveFragment(MemoryFragment fragment) {
        }

        @Override
        public java.util.Optional<MemoryFragment> retrieveFragment(String id) {
            return java.util.Optional.empty();
        }

        @Override
        public String saveCheckpoint(TaskState state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<TaskState> loadCheckpoint(String checkpointId) {
            return java.util.Optional.empty();
        }

        @Override
        public void deleteCheckpoint(String checkpointId) {
        }

        @Override
        public void putBytes(String key, byte[] data) {
        }

        @Override
        public byte[] getBytes(String key) {
            return null;
        }
    }

    private static final class ImmediatePageFaultHandler extends PageFaultHandler {
        private ImmediatePageFaultHandler(SemanticPageTable pageTable) {
            super(pageTable, null, null, null, new NoopEmbeddingService(), emptyProvider());
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> preloadPage(String pageId) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
}
