package com.vortex.kernel.paging;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.storage.api.L3ColdStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class PrefetchMetricsBinderTest {

    @Test
    void bindRegistersGlobalAndStrategyPrefetchMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SemanticPageTable pageTable = new SemanticPageTable(new NoopColdStore());
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
        PrefetchMetricsBinder binder = new PrefetchMetricsBinder(registry, engine);

        binder.bind();

        assertThat(registry.find("vortex.hmc.paging.prefetch.hit.rate").gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.paging.prefetch.strategy.hit.rate")
                .tags("source", "dag-topo")
                .gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.paging.prefetch.strategy.effective.budget")
                .tags("source", "semantic-nbhd")
                .gauge()).isNotNull();
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

    static final class ImmediatePageFaultHandler extends PageFaultHandler {
        private ImmediatePageFaultHandler(SemanticPageTable pageTable) {
            super(pageTable, null, null, null, new NoopEmbeddingService(), emptyProvider());
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> preloadPage(String pageId) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    static final class NoopEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{1.0f};
        }

        @Override
        public int dimension() {
            return 1;
        }
    }

    static final class NoopColdStore implements L3ColdStore {
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
