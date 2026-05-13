package com.vortex.kernel.paging;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Handles page faults — when a requested fragment is not in L1, load the
 * entire page containing it from L2/L3 into L1.
 *
 * Analogy: the OS page fault handler — transparently loads a page from
 * slower storage when the CPU accesses an unmapped virtual address.
 */
@Slf4j
@Component
public class PageFaultHandler {

    private final SemanticPageTable pageTable;
    private final L1HotStore l1;
    private final L2WarmStore l2;
    private final L3ColdStore l3;
    private final EmbeddingService embeddingService;
    private final ExecutorService virtualThreadExecutor;

    public PageFaultHandler(
            SemanticPageTable pageTable,
            L1HotStore l1,
            L2WarmStore l2,
            L3ColdStore l3,
            EmbeddingService embeddingService) {
        this.pageTable = pageTable;
        this.l1 = l1;
        this.l2 = l2;
        this.l3 = l3;
        this.embeddingService = embeddingService;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Handle a page fault for a given fragment ID.
     * <ol>
     *   <li>Look up the page containing this fragment</li>
     *   <li>If no page exists, dynamically build one</li>
     *   <li>Load all page fragments from L2 (batch) or L3 (individual fallback)</li>
     *   <li>Admit the entire page to L1</li>
     *   <li>Mark the page as RESIDENT</li>
     * </ol>
     *
     * @param fragmentId the fragment that triggered the fault
     * @param namespace  the namespace for context
     * @return future containing the loaded page
     */
    public CompletableFuture<SemanticPage> handlePageFault(String fragmentId, String namespace) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doHandlePageFault(fragmentId, namespace);
            } catch (Exception e) {
                log.error("Page fault handling failed fragmentId={} namespace={}: {}",
                        fragmentId, namespace, e.getMessage(), e);
                throw new RuntimeException("Page fault failed for " + fragmentId, e);
            }
        }, virtualThreadExecutor);
    }

    private SemanticPage doHandlePageFault(String fragmentId, String namespace) {
        // 1. Look up the page
        Optional<String> pageIdOpt = pageTable.lookup(fragmentId);
        SemanticPage page;

        if (pageIdOpt.isPresent()) {
            page = pageTable.getPage(pageIdOpt.get()).orElseThrow();
        } else {
            // Page doesn't exist yet — try to build one on the fly
            page = buildPageForFragment(fragmentId, namespace);
            if (page == null) {
                throw new IllegalStateException(
                        "Cannot resolve page for fragment " + fragmentId + " in namespace " + namespace);
            }
        }

        pageTable.markFaulting(page.getPageId());

        // 2. Load all fragments in the page from L2/L3
        List<MemoryFragment> loadedFragments = loadPageFromL2(page);
        if (loadedFragments.size() < page.getFragmentIds().size()) {
            Set<String> loadedIds = loadedFragments.stream()
                    .map(MemoryFragment::getId).collect(Collectors.toSet());
            for (String missingId : page.getFragmentIds()) {
                if (!loadedIds.contains(missingId)) {
                    loadFragmentFromL3(missingId).ifPresent(loadedFragments::add);
                }
            }
        }

        // 3. Admit all fragments to L1
        admitPageToL1(page, loadedFragments);

        // 4. Mark as resident
        pageTable.markResident(page.getPageId());
        log.debug("Page fault resolved pageId={} fragments={}", page.getPageId(), loadedFragments.size());

        return page;
    }

    /**
     * Load all fragments of a page from L2 (Milvus).
     * Uses individual get() calls since Milvus doesn't natively support bulk get by ID list.
     */
    List<MemoryFragment> loadPageFromL2(SemanticPage page) {
        List<MemoryFragment> results = new ArrayList<>();
        for (String fragmentId : page.getFragmentIds()) {
            Optional<MemoryFragment> found = l2.get(fragmentId);
            found.ifPresent(fragment -> {
                if (fragment.getEmbedding() == null) {
                    fragment.setEmbedding(embedFragment(fragment.getContent()));
                }
                results.add(fragment);
            });
        }
        return results;
    }

    /**
     * Load a single fragment from L3 (MinIO cold storage).
     */
    Optional<MemoryFragment> loadFragmentFromL3(String fragmentId) {
        Optional<MemoryFragment> fragment = l3.retrieveFragment(fragmentId);
        fragment.ifPresent(f -> {
            if (f.getEmbedding() == null) {
                f.setEmbedding(embedFragment(f.getContent()));
            }
        });
        return fragment;
    }

    /**
     * Admit an entire page of fragments to L1.
     * Each fragment is individually put into L1 — the caller (SemanticPagingManager)
     * should wrap this in a single admissionLock for atomicity.
     */
    void admitPageToL1(SemanticPage page, List<MemoryFragment> fragments) {
        for (MemoryFragment fragment : fragments) {
            fragment.recordAccess();
            l1.put(fragment);
        }
        page.recordAccess();
        log.debug("Page admitted to L1 pageId={} fragmentCount={}", page.getPageId(), fragments.size());
    }

    /**
     * Dynamically build a page for a fragment that doesn't belong to any page yet.
     * Searches L2 for semantically similar fragments and groups them.
     */
    private SemanticPage buildPageForFragment(String fragmentId, String namespace) {
        // Try to find the fragment in L2 or L3
        Optional<MemoryFragment> fragmentOpt = l2.get(fragmentId);
        if (fragmentOpt.isEmpty()) {
            fragmentOpt = l3.retrieveFragment(fragmentId);
        }
        if (fragmentOpt.isEmpty()) {
            return null;
        }

        MemoryFragment fragment = fragmentOpt.get();
        if (fragment.getEmbedding() == null) {
            fragment.setEmbedding(embedFragment(fragment.getContent()));
        }

        // Search L2 for semantically similar fragments
        List<MemoryFragment> neighbors = l2.search(fragment.getEmbedding(), namespace,
                SemanticPage.PAGE_SIZE);
        List<MemoryFragment> group = new ArrayList<>();
        group.add(fragment);
        Set<String> seen = new HashSet<>();
        seen.add(fragment.getId());

        for (MemoryFragment neighbor : neighbors) {
            if (group.size() >= SemanticPage.PAGE_SIZE) break;
            if (seen.add(neighbor.getId())) {
                if (neighbor.getEmbedding() == null) {
                    neighbor.setEmbedding(embedFragment(neighbor.getContent()));
                }
                group.add(neighbor);
            }
        }

        // Compute centroid
        float[] centroid = computeCentroid(group);
        String pageId = SemanticPage.buildPageId(centroid);

        SemanticPage page = SemanticPage.builder()
                .pageId(pageId)
                .centroid(centroid)
                .state(PageState.BUILDING)
                .build();

        for (MemoryFragment f : group) {
            page.addFragment(f.getId());
        }

        pageTable.putPage(page);
        log.debug("Dynamically built page pageId={} fragmentCount={}", pageId, group.size());
        return page;
    }

    /**
     * Load a page by ID and admit it to L1 (used by PrefetchEngine).
     */
    public CompletableFuture<Void> preloadPage(String pageId) {
        return CompletableFuture.runAsync(() -> {
            SemanticPage page = pageTable.getPage(pageId).orElseThrow(
                    () -> new IllegalArgumentException("Page not found: " + pageId));
            if (page.getState() == PageState.RESIDENT) return;

            pageTable.markFaulting(pageId);
            List<MemoryFragment> fragments = loadPageFromL2(page);
            if (fragments.size() < page.getFragmentIds().size()) {
                Set<String> loadedIds = fragments.stream()
                        .map(MemoryFragment::getId).collect(Collectors.toSet());
                for (String missingId : page.getFragmentIds()) {
                    if (!loadedIds.contains(missingId)) {
                        loadFragmentFromL3(missingId).ifPresent(fragments::add);
                    }
                }
            }
            admitPageToL1(page, fragments);
            pageTable.markResident(pageId);
        }, virtualThreadExecutor);
    }

    /** Compute the centroid (mean vector) of a group of fragments. */
    private static float[] computeCentroid(List<MemoryFragment> fragments) {
        if (fragments.isEmpty()) return new float[512];
        int dim = fragments.get(0).getEmbedding().length;
        float[] centroid = new float[dim];
        int count = 0;
        for (MemoryFragment f : fragments) {
            float[] emb = f.getEmbedding();
            if (emb == null) continue;
            for (int i = 0; i < dim; i++) {
                centroid[i] += emb[i];
            }
            count++;
        }
        if (count == 0) return centroid;
        for (int i = 0; i < dim; i++) {
            centroid[i] /= count;
        }
        return l2Normalize(centroid);
    }

    private static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    private float[] embedFragment(String content) {
        return embeddingService.embedAsync(content).join();
    }
}
