package com.vortex.kernel.paging;

import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import com.vortex.storage.api.L3ColdStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticPageTableTest {

    @Test
    void persistedPageTableCanBeLoadedWithConcurrentKeySets() {
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        SemanticPageTable writer = new SemanticPageTable(l3);

        SemanticPage page = SemanticPage.builder()
                .pageId("page-1")
                .centroid(new float[]{1.0f, 0.0f})
                .state(PageState.BUILDING)
                .build();
        page.addFragment("fragment-a");
        page.addFragment("fragment-b");
        page.associateDagNode("node-1");

        writer.putPage(page);
        writer.associateDagNode("node-1", page.getPageId());
        writer.registerFragment("fragment-c", page.getPageId());
        awaitPersistedSnapshot(l3);

        SemanticPageTable reader = new SemanticPageTable(l3);
        reader.loadPersistedPageTable();

        Optional<SemanticPage> loaded = reader.getPage("page-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().getState()).isEqualTo(PageState.EVICTED);
        assertThat(loaded.orElseThrow().getFragmentIds())
                .containsExactlyInAnyOrder("fragment-a", "fragment-b", "fragment-c");
        assertThat(loaded.orElseThrow().getDagNodeIds()).containsExactly("node-1");
        assertThat(reader.lookup("fragment-c")).contains("page-1");
        assertThat(reader.lookupPagesForDagNode("node-1")).containsExactly("page-1");
    }

    private static void awaitPersistedSnapshot(RecordingL3ColdStore l3) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (l3.hasBytes("system/semantic-page-table.bin")) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for semantic page table snapshot");
    }

    private static final class RecordingL3ColdStore implements L3ColdStore {
        private final Map<String, byte[]> bytes = new HashMap<>();

        @Override
        public void archiveFragment(com.vortex.common.model.MemoryFragment fragment) {
        }

        @Override
        public Optional<com.vortex.common.model.MemoryFragment> retrieveFragment(String id) {
            return Optional.empty();
        }

        @Override
        public String saveCheckpoint(com.vortex.common.model.TaskState state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.vortex.common.model.TaskState> loadCheckpoint(String checkpointId) {
            return Optional.empty();
        }

        @Override
        public void deleteCheckpoint(String checkpointId) {
        }

        @Override
        public void putBytes(String key, byte[] data) {
            bytes.put(key, data);
        }

        @Override
        public byte[] getBytes(String key) {
            return bytes.get(key);
        }

        private boolean hasBytes(String key) {
            return bytes.containsKey(key);
        }
    }
}
