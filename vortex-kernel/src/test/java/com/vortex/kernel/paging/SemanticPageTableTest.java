package com.vortex.kernel.paging;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.storage.api.L3ColdStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticPageTableTest {

    @Test
    void persistedPageTableCanBeLoadedWithConcurrentKeySets() {
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();

        SemanticPage page = SemanticPage.builder()
                .pageId("page-1")
                .centroid(new float[]{1.0f, 0.0f})
                .state(PageState.BUILDING)
                .build();
        page.addFragment("fragment-a");
        page.addFragment("fragment-b");
        page.addFragment("fragment-c");
        page.associateDagNode("node-1");
        Map<String, SemanticPage> pages = Map.of(page.getPageId(), page);
        Map<String, String> fragmentToPage = Map.of(
                "fragment-a", page.getPageId(),
                "fragment-b", page.getPageId(),
                "fragment-c", page.getPageId());
        Map<String, String> fragmentToPageWithOrphan = new HashMap<>(fragmentToPage);
        fragmentToPageWithOrphan.put("fragment-orphan", "missing-page");
        Map<String, java.util.Set<String>> dagNodeToPages = new HashMap<>();
        dagNodeToPages.put("node-1", new HashSet<>(java.util.Set.of(page.getPageId())));
        dagNodeToPages.put("node-orphan", new HashSet<>(java.util.Set.of("missing-page")));
        SemanticPageTable.PageTableSnapshot snapshot = new SemanticPageTable.PageTableSnapshot(
                new HashMap<>(pages),
                fragmentToPageWithOrphan,
                dagNodeToPages,
                2L);
        l3.putBytes(SemanticPageTable.DEFAULT_PAGE_TABLE_KEY, new KryoSerializer().serialize(snapshot));

        SemanticPageTable reader = new SemanticPageTable(l3);
        reader.loadPersistedPageTable();

        Optional<SemanticPage> loaded = reader.getPage("page-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().getState()).isEqualTo(PageState.EVICTED);
        assertThat(loaded.orElseThrow().getFragmentIds())
                .containsExactlyInAnyOrder("fragment-a", "fragment-b", "fragment-c");
        assertThat(loaded.orElseThrow().getDagNodeIds()).containsExactly("node-1");
        assertThat(reader.lookup("fragment-c")).contains("page-1");
        assertThat(reader.lookup("fragment-orphan")).isEmpty();
        assertThat(reader.lookupPagesForDagNode("node-1")).containsExactly("page-1");
        assertThat(reader.lookupPagesForDagNode("node-orphan")).isEmpty();
    }

    @Test
    void pageIdsRemainStableAcrossReloadAndIncrementalBuild() {
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        SemanticPageTable writer = new SemanticPageTable(l3);

        List<MemoryFragment> initialFragments = List.of(
                fragment("f-1", 1.0f, 0.0f),
                fragment("f-2", 0.98f, 0.02f),
                fragment("f-3", 0.96f, 0.04f),
                fragment("f-4", 0.94f, 0.06f),
                fragment("f-5", 0.0f, 1.0f),
                fragment("f-6", 0.02f, 0.98f),
                fragment("f-7", 0.04f, 0.96f),
                fragment("f-8", 0.06f, 0.94f));

        writer.buildPagesFromFragments(initialFragments);
        waitForPersistedPageTable(l3);

        Map<String, String> stableMappings = new HashMap<>();
        for (MemoryFragment fragment : initialFragments) {
            stableMappings.put(fragment.getId(), writer.lookup(fragment.getId()).orElseThrow());
        }
        int initialPageCount = writer.allPages().size();

        SemanticPageTable reloaded = new SemanticPageTable(l3);
        reloaded.loadPersistedPageTable();

        stableMappings.forEach((fragmentId, pageId) ->
                assertThat(reloaded.lookup(fragmentId)).contains(pageId));

        List<MemoryFragment> withNewFragments = new java.util.ArrayList<>(initialFragments);
        withNewFragments.add(fragment("f-9", 0.97f, 0.03f));
        withNewFragments.add(fragment("f-10", 0.03f, 0.97f));

        reloaded.buildPagesFromFragments(withNewFragments);

        stableMappings.forEach((fragmentId, pageId) ->
                assertThat(reloaded.lookup(fragmentId)).contains(pageId));
        assertThat(reloaded.lookup("f-9")).isPresent();
        assertThat(reloaded.lookup("f-10")).isPresent();
        assertThat(reloaded.allPages()).hasSizeGreaterThanOrEqualTo(initialPageCount);
    }

    @Test
    void incrementalAssignmentCreatesNewPageWhenNearestPageIsTooFar() {
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        SemanticPageTable table = new SemanticPageTable(l3, SemanticPageTable.DEFAULT_PAGE_TABLE_KEY, 0.05, 10);

        List<MemoryFragment> initialFragments = List.of(
                fragment("near-a", 1.0f, 0.0f),
                fragment("near-b", 0.99f, 0.01f),
                fragment("near-c", 0.98f, 0.02f),
                fragment("near-d", 0.97f, 0.03f));
        table.buildPagesFromFragments(initialFragments);
        int initialPageCount = table.allPages().size();

        List<SemanticPage> updatedPages = table.buildPagesFromFragments(List.of(fragment("far-away", 0.0f, 1.0f)));

        assertThat(updatedPages).hasSize(1);
        assertThat(table.allPages()).hasSize(initialPageCount + 1);
        assertThat(table.lookup("far-away")).isPresent();
    }

    @Test
    void configuredPageSizeLimitsIncrementalPageCapacity() {
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        SemanticPageTable table = new SemanticPageTable(l3, SemanticPageTable.DEFAULT_PAGE_TABLE_KEY, 0.30, 2);

        table.buildPagesFromFragments(List.of(
                fragment("a", 1.0f, 0.0f),
                fragment("b", 0.99f, 0.01f),
                fragment("c", 0.98f, 0.02f)));

        assertThat(table.allPages()).hasSize(2);
        assertThat(table.allPages().stream().mapToInt(page -> page.getFragmentIds().size()).max().orElseThrow())
                .isLessThanOrEqualTo(2);
    }

    private static MemoryFragment fragment(String id, float x, float y) {
        return MemoryFragment.builder()
                .id(id)
                .namespace("ns")
                .content(id)
                .embedding(new float[]{x, y})
                .build();
    }

    private static void waitForPersistedPageTable(RecordingL3ColdStore l3) {
        for (int i = 0; i < 50; i++) {
            if (l3.getBytes(SemanticPageTable.DEFAULT_PAGE_TABLE_KEY) != null) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for persisted page table", e);
            }
        }
        throw new AssertionError("Timed out waiting for persisted page table");
    }

    private static final class RecordingL3ColdStore implements L3ColdStore {
        private final Map<String, byte[]> bytes = new ConcurrentHashMap<>();

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
    }
}
