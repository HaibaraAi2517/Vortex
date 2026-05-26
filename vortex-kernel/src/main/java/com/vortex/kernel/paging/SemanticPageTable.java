package com.vortex.kernel.paging;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.storage.api.L3ColdStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Page table that maps fragments → pages and DAG nodes → pages.
 *
 * Analogy: the kernel page table in an OS — translates virtual addresses
 * (fragment IDs / DAG node IDs) to physical pages (SemanticPage).
 */
@Slf4j
@Component
public class SemanticPageTable {

    static final String DEFAULT_PAGE_TABLE_KEY = "system/semantic-page-table.bin";

    private final ConcurrentMap<String, String> fragmentToPage = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> dagNodeToPages = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SemanticPage> pages = new ConcurrentHashMap<>();
    private final L3ColdStore l3;
    private final String pageTableKey;
    private final double maxIncrementalAssignmentDistance;
    private final int pageSize;
    private final KryoSerializer kryoSerializer = new KryoSerializer();
    private final Object snapshotLock = new Object();
    private final AtomicLong lastPersistEpoch = new AtomicLong(0);
    private final AtomicLong nextPageSequence = new AtomicLong(1);
    private final AtomicLong incrementalAssignmentCount = new AtomicLong();
    private final AtomicLong incrementalReuseCount = new AtomicLong();
    private final AtomicLong incrementalNewPageCount = new AtomicLong();
    private static final long PERSIST_INTERVAL_MS = Duration.ofSeconds(30).toMillis();

    public SemanticPageTable(L3ColdStore l3) {
        this(l3, DEFAULT_PAGE_TABLE_KEY, 0.30, 10);
    }

    @Autowired
    public SemanticPageTable(
            L3ColdStore l3,
            @Value("${vortex.kernel.paging.page-table-key:" + DEFAULT_PAGE_TABLE_KEY + "}") String pageTableKey,
            @Value("${vortex.kernel.paging.incremental.max-distance:0.30}") double maxIncrementalAssignmentDistance,
            @Value("${vortex.kernel.paging.page-size:10}") int pageSize) {
        this.l3 = l3;
        this.pageTableKey = pageTableKey;
        this.maxIncrementalAssignmentDistance = Math.max(0.0, maxIncrementalAssignmentDistance);
        this.pageSize = Math.max(1, pageSize);
    }

    @PostConstruct
    void loadPersistedPageTable() {
        try {
            byte[] data = l3.getBytes(pageTableKey);
            if (data == null || data.length == 0) {
                return;
            }
            PageTableSnapshot snapshot = kryoSerializer.deserialize(data, PageTableSnapshot.class);
            Map<String, SemanticPage> snapshotPages =
                    snapshot.getPages() != null ? snapshot.getPages() : Map.of();
            Map<String, String> snapshotFragmentToPage = new HashMap<>();
            if (snapshot.getFragmentToPage() != null) {
                snapshot.getFragmentToPage().forEach((fragmentId, pageId) -> {
                    if (snapshotPages.containsKey(pageId)) {
                        snapshotFragmentToPage.put(fragmentId, pageId);
                    }
                });
            }
            Map<String, Set<String>> snapshotDagNodeToPages = new HashMap<>();
            if (snapshot.getDagNodeToPages() != null) {
                snapshot.getDagNodeToPages().forEach((nodeId, pageIds) -> {
                    Set<String> filteredPageIds = new HashSet<>();
                    if (pageIds != null) {
                        for (String pageId : pageIds) {
                            if (snapshotPages.containsKey(pageId)) {
                                filteredPageIds.add(pageId);
                            }
                        }
                    }
                    if (!filteredPageIds.isEmpty()) {
                        snapshotDagNodeToPages.put(nodeId, filteredPageIds);
                    }
                });
            }
            synchronized (snapshotLock) {
                pages.clear();
                snapshotPages.forEach((pageId, page) -> {
                    page.setState(PageState.EVICTED);
                    pages.put(pageId, page);
                });
                fragmentToPage.clear();
                fragmentToPage.putAll(snapshotFragmentToPage);
                dagNodeToPages.clear();
                snapshotDagNodeToPages.forEach((nodeId, pageIds) ->
                        dagNodeToPages.put(nodeId, ConcurrentHashMap.newKeySet(Math.max(1, pageIds.size()))));
                snapshotDagNodeToPages.forEach((nodeId, pageIds) ->
                        dagNodeToPages.get(nodeId).addAll(pageIds));
                nextPageSequence.set(snapshot.getNextPageSequence() > 0
                        ? snapshot.getNextPageSequence()
                        : deriveNextPageSequence(snapshotPages.keySet()));
            }
            log.info("Loaded persisted page table: {} pages, {} fragment mappings",
                    snapshotPages.size(), snapshotFragmentToPage.size());
        } catch (Exception e) {
            log.warn("Page table load failed, will rebuild on demand: {}", e.getMessage());
        }
    }

    /** Look up the page ID for a given fragment. */
    public Optional<String> lookup(String fragmentId) {
        return Optional.ofNullable(fragmentToPage.get(fragmentId));
    }

    /** Look up the page for a given fragment. */
    public Optional<SemanticPage> lookupPage(String fragmentId) {
        return lookup(fragmentId).map(pages::get);
    }

    /** Get all page IDs associated with a DAG node. */
    public Set<String> lookupPagesForDagNode(String nodeId) {
        return dagNodeToPages.getOrDefault(nodeId, Set.of());
    }

    /** Look up a full page by its ID. */
    public Optional<SemanticPage> getPage(String pageId) {
        return Optional.ofNullable(pages.get(pageId));
    }

    /** Get all pages. */
    public Collection<SemanticPage> allPages() {
        return Collections.unmodifiableCollection(pages.values());
    }

    /** Get all resident pages. */
    public List<SemanticPage> residentPages() {
        return pages.values().stream()
                .filter(p -> p.getState() == PageState.RESIDENT)
                .toList();
    }

    /** Register a fragment → page mapping. */
    public void registerFragment(String fragmentId, String pageId) {
        synchronized (snapshotLock) {
            fragmentToPage.put(fragmentId, pageId);
            SemanticPage page = pages.get(pageId);
            if (page != null) {
                page.addFragment(fragmentId);
            }
        }
        persistIfNeeded();
    }

    /** Associate a DAG node with a page. */
    public void associateDagNode(String nodeId, String pageId) {
        synchronized (snapshotLock) {
            dagNodeToPages.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet()).add(pageId);
            SemanticPage page = pages.get(pageId);
            if (page != null) {
                page.associateDagNode(nodeId);
            }
        }
        persistIfNeeded();
    }

    /** Mark a page as RESIDENT in L1. */
    public void markResident(String pageId) {
        SemanticPage page = pages.get(pageId);
        if (page != null) {
            page.setState(PageState.RESIDENT);
            page.recordAccess();
        }
    }

    /** Mark a page as EVICTED from L1. */
    public void markEvicted(String pageId) {
        SemanticPage page = pages.get(pageId);
        if (page != null) {
            page.setState(PageState.EVICTED);
        }
    }

    /** Mark a page as FAULTING (being loaded). */
    public void markFaulting(String pageId) {
        SemanticPage page = pages.get(pageId);
        if (page != null) {
            page.setState(PageState.FAULTING);
        }
    }

    /** Record co-access between two fragments for page re-organization. */
    public void recordCoAccess(String fragmentIdA, String fragmentIdB) {
        if (Objects.equals(fragmentIdA, fragmentIdB)) return;
        lookupPage(fragmentIdA).ifPresent(page -> page.recordCoAccess(fragmentIdB));
        lookupPage(fragmentIdB).ifPresent(page -> page.recordCoAccess(fragmentIdA));
    }

    /** Insert a fully built page into the table. */
    public void putPage(SemanticPage page) {
        synchronized (snapshotLock) {
            pages.put(page.getPageId(), page);
            for (String fragmentId : page.getFragmentIds()) {
                fragmentToPage.put(fragmentId, page.getPageId());
            }
        }
        persistIfNeeded();
    }

    /**
     * Build pages from a list of fragments using K-Means clustering on their embeddings.
     *
     * @param fragments list of fragments with embeddings
     * @return list of built pages
     */
    public List<SemanticPage> buildPagesFromFragments(List<MemoryFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }

        // Filter fragments with valid embeddings
        List<MemoryFragment> validFragments = fragments.stream()
                .filter(f -> f.getEmbedding() != null && f.getEmbedding().length > 0)
                .toList();

        if (validFragments.isEmpty()) {
            return List.of();
        }

        List<MemoryFragment> unassignedFragments = validFragments.stream()
                .filter(fragment -> !fragmentToPage.containsKey(fragment.getId()))
                .toList();
        if (unassignedFragments.isEmpty()) {
            return List.of();
        }

        if (!pages.isEmpty()) {
            return assignFragmentsIncrementally(unassignedFragments);
        }

        return clusterNewPages(unassignedFragments);
    }

    private List<SemanticPage> clusterNewPages(List<MemoryFragment> fragments) {
        int k = Math.max(1, Math.min((int) Math.ceil(fragments.size() / (double) pageSize), fragments.size()));
        int dim = fragments.get(0).getEmbedding().length;

        // K-Means clustering
        float[][] centroids = initializeCentroids(fragments, k, dim);
        Map<Integer, List<MemoryFragment>> clusters = new HashMap<>();

        for (int iter = 0; iter < 10; iter++) {
            clusters.clear();
            for (int i = 0; i < k; i++) {
                clusters.put(i, new ArrayList<>());
            }
            // Assign each fragment to nearest centroid
            for (MemoryFragment fragment : fragments) {
                int best = 0;
                double bestDist = Double.MAX_VALUE;
                for (int ci = 0; ci < k; ci++) {
                    double dist = cosineDistance(fragment.getEmbedding(), centroids[ci]);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = ci;
                    }
                }
                clusters.get(best).add(fragment);
            }
            // Recompute centroids
            boolean changed = false;
            for (int ci = 0; ci < k; ci++) {
                float[] newCentroid = computeMean(clusters.get(ci), dim);
                if (newCentroid == null) continue;
                if (cosineDistance(centroids[ci], newCentroid) > 0.001) {
                    changed = true;
                }
                centroids[ci] = l2Normalize(newCentroid);
            }
            if (!changed) break;
        }

        // Build SemanticPages from clusters
        List<SemanticPage> result = new ArrayList<>();
        synchronized (snapshotLock) {
            for (int ci = 0; ci < k; ci++) {
                List<MemoryFragment> cluster = clusters.get(ci);
                if (cluster.isEmpty()) continue;

                float[] centroid = l2Normalize(computeMean(cluster, dim));
                String pageId = allocatePageId();

                SemanticPage page = SemanticPage.builder()
                        .pageId(pageId)
                        .centroid(centroid)
                        .state(PageState.BUILDING)
                        .build();

                for (MemoryFragment fragment : cluster) {
                    page.addFragment(fragment.getId());
                    fragmentToPage.put(fragment.getId(), pageId);
                }
                pages.put(pageId, page);
                result.add(page);
            }
        }

        persistIfNeeded();
        log.info("Built {} pages from {} fragments (K={})", result.size(), fragments.size(), k);
        return result;
    }

    private List<SemanticPage> assignFragmentsIncrementally(List<MemoryFragment> fragments) {
        Map<String, SemanticPage> touchedPages = new LinkedHashMap<>();
        synchronized (snapshotLock) {
            for (MemoryFragment fragment : fragments) {
                Optional<SemanticPage> nearest = findNearestPageWithCapacity(fragment);
                SemanticPage targetPage;
                if (nearest.isPresent()) {
                    targetPage = nearest.get();
                    incrementalReuseCount.incrementAndGet();
                } else {
                    targetPage = createEmptyPage(fragment.getEmbedding());
                    incrementalNewPageCount.incrementAndGet();
                }
                incrementalAssignmentCount.incrementAndGet();
                addFragmentToPage(targetPage, fragment);
                touchedPages.put(targetPage.getPageId(), targetPage);
            }
        }
        persistIfNeeded();
        log.info("Incrementally assigned {} fragments across {} pages", fragments.size(), touchedPages.size());
        return new ArrayList<>(touchedPages.values());
    }

    public AssignmentStats assignmentStats() {
        long assignments = incrementalAssignmentCount.get();
        long reused = incrementalReuseCount.get();
        long newPages = incrementalNewPageCount.get();
        double reuseRatio = assignments == 0 ? 0.0 : reused / (double) assignments;
        double newPageRatio = assignments == 0 ? 0.0 : newPages / (double) assignments;
        return new AssignmentStats(assignments, reused, newPages, reuseRatio, newPageRatio);
    }

    public int pageSize() {
        return pageSize;
    }

    private void persistIfNeeded() {
        long now = System.currentTimeMillis();
        long previous = lastPersistEpoch.get();
        if (now - previous < PERSIST_INTERVAL_MS) {
            return;
        }
        if (!lastPersistEpoch.compareAndSet(previous, now)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                PageTableSnapshot snapshot;
                synchronized (snapshotLock) {
                    Map<String, Set<String>> dagSnapshot = new HashMap<>();
                    dagNodeToPages.forEach((nodeId, pageIds) -> dagSnapshot.put(nodeId, new HashSet<>(pageIds)));
                    Map<String, SemanticPage> pageSnapshot = new HashMap<>();
                    pages.forEach((pageId, page) -> pageSnapshot.put(pageId, copyPage(page)));
                    snapshot = new PageTableSnapshot(
                            pageSnapshot,
                            new HashMap<>(fragmentToPage),
                            dagSnapshot,
                            nextPageSequence.get());
                }
                l3.putBytes(pageTableKey, kryoSerializer.serialize(snapshot));
                log.debug("Page table persisted: pages={} fragments={}",
                        snapshot.getPages().size(), snapshot.getFragmentToPage().size());
            } catch (Exception e) {
                log.error("Page table persist failed: {}", e.getMessage(), e);
            }
        });
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class PageTableSnapshot {
        private Map<String, SemanticPage> pages;
        private Map<String, String> fragmentToPage;
        private Map<String, Set<String>> dagNodeToPages;
        private long nextPageSequence;
    }

    private Optional<SemanticPage> findNearestPageWithCapacity(MemoryFragment fragment) {
        float[] embedding = fragment.getEmbedding();
        if (embedding == null || embedding.length == 0) {
            return Optional.empty();
        }
        return pages.values().stream()
                .filter(page -> page.getFragmentIds().size() < pageSize)
                .filter(page -> page.getCentroid() != null && page.getCentroid().length == embedding.length)
                .map(page -> new PageDistance(page, cosineDistance(embedding, page.getCentroid())))
                .filter(candidate -> candidate.distance() <= maxIncrementalAssignmentDistance)
                .min(Comparator.comparingDouble(PageDistance::distance))
                .map(PageDistance::page);
    }

    private SemanticPage createEmptyPage(float[] seedEmbedding) {
        String pageId = allocatePageId();
        SemanticPage page = SemanticPage.builder()
                .pageId(pageId)
                .centroid(seedEmbedding == null ? null : l2Normalize(Arrays.copyOf(seedEmbedding, seedEmbedding.length)))
                .state(PageState.BUILDING)
                .build();
        pages.put(pageId, page);
        return page;
    }

    private void addFragmentToPage(SemanticPage page, MemoryFragment fragment) {
        int existingCount = page.getFragmentIds().size();
        page.addFragment(fragment.getId());
        fragmentToPage.put(fragment.getId(), page.getPageId());
        page.setCentroid(updateCentroid(page.getCentroid(), existingCount, fragment.getEmbedding()));
    }

    private float[] updateCentroid(float[] currentCentroid, int existingCount, float[] newEmbedding) {
        if (newEmbedding == null || newEmbedding.length == 0) {
            return currentCentroid;
        }
        if (currentCentroid == null || currentCentroid.length != newEmbedding.length || existingCount <= 0) {
            return l2Normalize(Arrays.copyOf(newEmbedding, newEmbedding.length));
        }
        float[] mean = new float[newEmbedding.length];
        for (int i = 0; i < newEmbedding.length; i++) {
            mean[i] = ((currentCentroid[i] * existingCount) + newEmbedding[i]) / (existingCount + 1);
        }
        return l2Normalize(mean);
    }

    private String allocatePageId() {
        return "page-" + nextPageSequence.getAndIncrement();
    }

    private long deriveNextPageSequence(Collection<String> pageIds) {
        long maxSequence = pageIds.stream()
                .mapToLong(this::extractPageSequence)
                .max()
                .orElse(0L);
        return maxSequence > 0 ? maxSequence + 1 : pageIds.size() + 1L;
    }

    private long extractPageSequence(String pageId) {
        if (pageId == null || !pageId.startsWith("page-")) {
            return 0L;
        }
        try {
            return Long.parseLong(pageId.substring("page-".length()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private SemanticPage copyPage(SemanticPage page) {
        return SemanticPage.builder()
                .pageId(page.getPageId())
                .centroid(page.getCentroid() == null ? null : Arrays.copyOf(page.getCentroid(), page.getCentroid().length))
                .fragmentIds(new HashSet<>(page.getFragmentIds()))
                .dagNodeIds(new HashSet<>(page.getDagNodeIds()))
                .accessCount(page.getAccessCount())
                .lastAccessTime(page.getLastAccessTime())
                .state(page.getState())
                .coAccessStats(new HashMap<>(page.getCoAccessStats()))
                .build();
    }

    // ---- K-Means helpers ----

    private float[][] initializeCentroids(List<MemoryFragment> fragments, int k, int dim) {
        float[][] centroids = new float[k][dim];
        // K-Means++ style: pick first centroid randomly, then farthest-first
        int first = ThreadLocalRandom.current().nextInt(fragments.size());
        System.arraycopy(fragments.get(first).getEmbedding(), 0, centroids[0], 0, dim);

        for (int ci = 1; ci < k; ci++) {
            double maxDist = -1;
            int bestIdx = 0;
            for (int i = 0; i < fragments.size(); i++) {
                double minDistToCentroids = Double.MAX_VALUE;
                for (int j = 0; j < ci; j++) {
                    double d = cosineDistance(fragments.get(i).getEmbedding(), centroids[j]);
                    if (d < minDistToCentroids) {
                        minDistToCentroids = d;
                    }
                }
                if (minDistToCentroids > maxDist) {
                    maxDist = minDistToCentroids;
                    bestIdx = i;
                }
            }
            System.arraycopy(fragments.get(bestIdx).getEmbedding(), 0, centroids[ci], 0, dim);
        }
        return centroids;
    }

    private float[] computeMean(List<MemoryFragment> cluster, int dim) {
        if (cluster.isEmpty()) return null;
        float[] mean = new float[dim];
        for (MemoryFragment f : cluster) {
            float[] emb = f.getEmbedding();
            if (emb == null) continue;
            for (int i = 0; i < dim; i++) {
                mean[i] += emb[i];
            }
        }
        int count = cluster.size();
        for (int i = 0; i < dim; i++) {
            mean[i] /= count;
        }
        return mean;
    }

    /** Cosine distance = 1 - cosine similarity (range [0, 2]). */
    private static double cosineDistance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 2.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 2.0 : 1.0 - (dot / denom);
    }

    /** L2-normalize a vector in place. */
    private static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    private record PageDistance(SemanticPage page, double distance) {
    }

    public record AssignmentStats(
            long incrementalAssignments,
            long incrementalReuseAssignments,
            long incrementalNewPageAssignments,
            double incrementalReuseRatio,
            double incrementalNewPageRatio) {
    }
}
