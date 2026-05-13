package com.vortex.kernel.paging;

import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagGraph;
import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * Prefetch engine with three strategies:
 *
 * <ol>
 *   <li><b>DAG topology prefetch</b> (priority 10) — triggered by node append/complete/edge events.
 *       BFS from the current node (max depth 2) to find lookahead nodes and their pages.</li>
 *   <li><b>Semantic neighborhood prefetch</b> (priority 5) — triggered by recall embedding.
 *       Finds pages whose centroids are close to the query embedding.</li>
 *   <li><b>Branch speculative prefetch</b> (priority 2) — triggered by branch creation.
 *       Prefetches source node pages for each active branch (max 1 page per branch).</li>
 * </ol>
 *
 * All prefetch tasks are executed on virtual threads via {@link #virtualThreadExecutor}.
 */
@Slf4j
@Component
public class PrefetchEngine {

    private static final int MAX_PREFETCH_QUEUE_SIZE = 500;

    private final SemanticPageTable pageTable;
    private final PageFaultHandler pageFaultHandler;
    private final ExecutorService virtualThreadExecutor;

    private final PriorityBlockingQueue<PrefetchTask> prefetchQueue = new PriorityBlockingQueue<>();
    private final Set<String> inflightPages = ConcurrentHashMap.newKeySet();

    // Hit-rate tracking for stats
    private final ConcurrentMap<String, Long> prefetchRequested = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> prefetchHit = new ConcurrentHashMap<>();

    private final int dagMaxDepth;
    private final int dagMaxPages;
    private final int semanticMaxPages;
    private final double centroidSimilarityThreshold;
    private final int branchMaxPagesPerBranch;

    public PrefetchEngine(
            SemanticPageTable pageTable,
            PageFaultHandler pageFaultHandler,
            @Value("${vortex.kernel.paging.prefetch.dag-topology.max-depth:2}") int dagMaxDepth,
            @Value("${vortex.kernel.paging.prefetch.dag-topology.max-pages:3}") int dagMaxPages,
            @Value("${vortex.kernel.paging.prefetch.semantic-neighborhood.max-pages:3}") int semanticMaxPages,
            @Value("${vortex.kernel.paging.prefetch.semantic-neighborhood.centroid-similarity-threshold:0.7}") double centroidSimilarityThreshold,
            @Value("${vortex.kernel.paging.prefetch.branch-speculative.max-pages-per-branch:1}") int branchMaxPagesPerBranch) {
        this.pageTable = pageTable;
        this.pageFaultHandler = pageFaultHandler;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.dagMaxDepth = dagMaxDepth;
        this.dagMaxPages = dagMaxPages;
        this.semanticMaxPages = semanticMaxPages;
        this.centroidSimilarityThreshold = centroidSimilarityThreshold;
        this.branchMaxPagesPerBranch = branchMaxPagesPerBranch;

        // Start prefetch worker
        Thread worker = Thread.ofVirtual()
                .name("prefetch-worker")
                .start(this::prefetchWorkerLoop);
        log.info("PrefetchEngine initialized: dagDepth={}, dagPages={}, semPages={}, branchPages={}",
                dagMaxDepth, dagMaxPages, semanticMaxPages, branchMaxPagesPerBranch);
    }

    // ========================================================================
    // Strategy 1: DAG topology prefetch (priority 10)
    // ========================================================================

    /**
     * Triggered on DAG change events (node appended, node completed, edge added, branch switched).
     * Performs BFS from the current node and prefetches pages associated with lookahead nodes.
     */
    public void onDagChange(String taskId, String currentNodeId, DagGraph graph) {
        if (currentNodeId == null || graph == null) return;

        try {
            // BFS from currentNodeId up to maxDepth
            Set<String> lookaheadNodes = bfsLookahead(currentNodeId, graph, dagMaxDepth);
            if (lookaheadNodes.isEmpty()) return;

            // Collect all pages associated with lookahead nodes
            Set<String> candidatePageIds = new HashSet<>();
            for (String nodeId : lookaheadNodes) {
                candidatePageIds.addAll(pageTable.lookupPagesForDagNode(nodeId));
            }

            // Filter: skip RESIDENT and FAULTING pages
            List<PrefetchTask> tasks = candidatePageIds.stream()
                    .map(pageTable::getPage)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(p -> p.getState() != PageState.RESIDENT && p.getState() != PageState.FAULTING)
                    .sorted(Comparator.comparingLong(SemanticPage::getAccessCount).reversed())
                    .limit(dagMaxPages)
                    .map(p -> new PrefetchTask(p.getPageId(), 10, "dag-topo"))
                    .toList();

            for (PrefetchTask task : tasks) {
                submitPrefetch(task);
            }

            if (!tasks.isEmpty()) {
                log.debug("DAG topology prefetch: queued {} pages for task={} node={}",
                        tasks.size(), taskId, currentNodeId);
            }
        } catch (Exception e) {
            log.warn("DAG topology prefetch failed taskId={} nodeId={}: {}", taskId, currentNodeId, e.getMessage());
        }
    }

    // ========================================================================
    // Strategy 2: Semantic neighborhood prefetch (priority 5)
    // ========================================================================

    /**
     * Triggered on recall. Finds pages whose centroids are semantically close
     * to the query embedding, and prefetches them.
     */
    public void onRecall(float[] queryEmbedding) {
        if (queryEmbedding == null) return;

        try {
            Collection<SemanticPage> allPages = pageTable.allPages();

            // Score each page by centroid similarity to the query
            List<ScoredPage> scored = allPages.stream()
                    .filter(p -> p.getState() != PageState.RESIDENT && p.getState() != PageState.FAULTING)
                    .filter(p -> p.getCentroid() != null)
                    .map(p -> new ScoredPage(p, cosineSimilarity(p.getCentroid(), queryEmbedding)))
                    .filter(sp -> sp.similarity >= centroidSimilarityThreshold)
                    .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                    .limit(semanticMaxPages)
                    .toList();

            for (ScoredPage sp : scored) {
                submitPrefetch(new PrefetchTask(sp.page.getPageId(), 5, "semantic-nbhd"));
            }

            if (!scored.isEmpty()) {
                log.debug("Semantic neighborhood prefetch: queued {} pages", scored.size());
            }
        } catch (Exception e) {
            log.warn("Semantic neighborhood prefetch failed: {}", e.getMessage());
        }
    }

    // ========================================================================
    // Strategy 3: Branch speculative prefetch (priority 2)
    // ========================================================================

    /**
     * Triggered when a new branch is created. Prefetches source node pages
     * with conservative limits (1 page per branch).
     */
    public void onBranchCreated(String taskId, String branchName, String sourceNodeId) {
        if (sourceNodeId == null) return;

        try {
            Set<String> pageIds = pageTable.lookupPagesForDagNode(sourceNodeId);
            pageIds.stream()
                    .map(pageTable::getPage)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(p -> p.getState() != PageState.RESIDENT && p.getState() != PageState.FAULTING)
                    .limit(branchMaxPagesPerBranch)
                    .forEach(p -> submitPrefetch(new PrefetchTask(p.getPageId(), 2, "branch-spec")));

            log.debug("Branch speculative prefetch for task={} branch={} sourceNode={}",
                    taskId, branchName, sourceNodeId);
        } catch (Exception e) {
            log.warn("Branch speculative prefetch failed: {}", e.getMessage());
        }
    }

    /**
     * Triggered when switching to a different branch.
     */
    public void onBranchSwitched(String taskId, String branchId, String currentNodeId) {
        // When switching branches, the current node changes — use DAG topology prefetch
        log.debug("Branch switched: task={} branch={} node={}", taskId, branchId, currentNodeId);
    }

    // ========================================================================
    // Page preloading
    // ========================================================================

    /**
     * Preload a single page into L1. Called by the prefetch worker.
     */
    public CompletableFuture<Void> preloadPage(String pageId) {
        if (!inflightPages.add(pageId)) {
            return CompletableFuture.completedFuture(null); // Already loading
        }
        prefetchRequested.merge(pageId, 1L, Long::sum);
        return pageFaultHandler.preloadPage(pageId)
                .whenComplete((v, ex) -> {
                    inflightPages.remove(pageId);
                    if (ex == null) {
                        prefetchHit.merge(pageId, 1L, Long::sum);
                    } else {
                        log.debug("Prefetch failed for page={}: {}", pageId, ex.getMessage());
                    }
                });
    }

    // ========================================================================
    // Stats
    // ========================================================================

    public record PrefetchStats(
            long totalRequested,
            long totalHit,
            double hitRate,
            long queued,
            long inflight
    ) {}

    public PrefetchStats getStats() {
        long requested = prefetchRequested.values().stream().mapToLong(Long::longValue).sum();
        long hit = prefetchHit.values().stream().mapToLong(Long::longValue).sum();
        double hitRate = requested > 0 ? (double) hit / requested : 0.0;
        return new PrefetchStats(requested, hit, hitRate, prefetchQueue.size(), inflightPages.size());
    }

    // ========================================================================
    // Internal: worker loop & BFS
    // ========================================================================

    private void prefetchWorkerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PrefetchTask task = prefetchQueue.poll(1, TimeUnit.SECONDS);
                if (task == null) continue;

                preloadPage(task.pageId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Prefetch worker error: {}", e.getMessage());
            }
        }
    }

    private void submitPrefetch(PrefetchTask task) {
        if (prefetchQueue.size() >= MAX_PREFETCH_QUEUE_SIZE) {
            log.debug("Prefetch queue at capacity ({}), dropping task: {} priority={}",
                    MAX_PREFETCH_QUEUE_SIZE, task.source(), task.priority());
            return;
        }
        prefetchQueue.offer(task);
    }

    /**
     * BFS from startNodeId for maxDepth hops, returning all reachable node IDs.
     */
    private Set<String> bfsLookahead(String startNodeId, DagGraph graph, int maxDepth) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        queue.add(startNodeId);
        depths.add(0);
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depths.poll();
            if (depth >= maxDepth) continue;

            for (DagEdge edge : graph.getOutgoingEdges(current)) {
                String target = edge.getTargetNodeId();
                if (visited.add(target)) {
                    queue.add(target);
                    depths.add(depth + 1);
                }
            }
        }
        visited.remove(startNodeId); // Exclude the current node
        return visited;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    // ---- Internal types ----

    private record PrefetchTask(String pageId, int priority, String source)
            implements Comparable<PrefetchTask> {
        @Override
        public int compareTo(PrefetchTask other) {
            // Higher priority first
            return Integer.compare(other.priority, this.priority);
        }
    }

    private record ScoredPage(SemanticPage page, double similarity) {}
}
