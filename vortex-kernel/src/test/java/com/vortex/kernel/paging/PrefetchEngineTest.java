package com.vortex.kernel.paging;

import com.vortex.common.model.MemoryFragment;
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
                false);

        engine.submitPrefetchForTest("page-branch", 2, "branch");
        engine.submitPrefetchForTest("page-semantic", 5, "semantic");

        assertThat(engine.submitPrefetchForTest("page-dag", 10, "dag")).isTrue();

        assertThat(engine.queuedTaskCountForTest()).isEqualTo(2);
        assertThat(engine.queuedPageIdsForTest()).containsExactly("page-dag", "page-semantic");
        assertThat(engine.queuedPageIdsForTest()).doesNotContain("page-branch");
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
}
