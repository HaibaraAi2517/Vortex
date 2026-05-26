package com.vortex.kernel.paging;

import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagGraph;
import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
    public static final List<String> METRIC_STRATEGY_SOURCES = List.of("dag-topo", "semantic-nbhd", "branch-spec", "manual");

    private final SemanticPageTable pageTable;
    private final PageFaultHandler pageFaultHandler;
    private final ExecutorService virtualThreadExecutor;

    private final BoundedPrefetchQueue prefetchQueue;
    private final Set<String> inflightPages = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, PrefetchObservation> prefetchedPages = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StrategyControlState> strategyStates = new ConcurrentHashMap<>();

    private final int dagMaxDepth;
    private final int dagMaxPages;
    private final int semanticMaxPages;
    private final double centroidSimilarityThreshold;
    private final int branchMaxPagesPerBranch;
    private final long consumptionWindowMs;
    private final int minStrategySamples;

    @Autowired
    public PrefetchEngine(
            SemanticPageTable pageTable,
            PageFaultHandler pageFaultHandler,
            @Value("${vortex.kernel.paging.prefetch.dag-topology.max-depth:2}") int dagMaxDepth,
            @Value("${vortex.kernel.paging.prefetch.dag-topology.max-pages:3}") int dagMaxPages,
            @Value("${vortex.kernel.paging.prefetch.semantic-neighborhood.max-pages:3}") int semanticMaxPages,
            @Value("${vortex.kernel.paging.prefetch.semantic-neighborhood.centroid-similarity-threshold:0.7}") double centroidSimilarityThreshold,
            @Value("${vortex.kernel.paging.prefetch.branch-speculative.max-pages-per-branch:1}") int branchMaxPagesPerBranch,
            @Value("${vortex.kernel.paging.prefetch.queue.max-size:500}") int maxPrefetchQueueSize,
            @Value("${vortex.kernel.paging.prefetch.consumption-window-ms:120000}") long consumptionWindowMs,
            @Value("${vortex.kernel.paging.prefetch.strategy.min-samples:8}") int minStrategySamples) {
        this(pageTable, pageFaultHandler, dagMaxDepth, dagMaxPages, semanticMaxPages,
                centroidSimilarityThreshold, branchMaxPagesPerBranch, maxPrefetchQueueSize,
                consumptionWindowMs, minStrategySamples, true);
    }

    PrefetchEngine(
            SemanticPageTable pageTable,
            PageFaultHandler pageFaultHandler,
            int dagMaxDepth,
            int dagMaxPages,
            int semanticMaxPages,
            double centroidSimilarityThreshold,
            int branchMaxPagesPerBranch,
            int maxPrefetchQueueSize,
            long consumptionWindowMs,
            int minStrategySamples,
            boolean startWorker) {
        this.pageTable = pageTable;
        this.pageFaultHandler = pageFaultHandler;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.prefetchQueue = new BoundedPrefetchQueue(Math.max(1, maxPrefetchQueueSize));
        this.dagMaxDepth = dagMaxDepth;
        this.dagMaxPages = dagMaxPages;
        this.semanticMaxPages = semanticMaxPages;
        this.centroidSimilarityThreshold = centroidSimilarityThreshold;
        this.branchMaxPagesPerBranch = branchMaxPagesPerBranch;
        this.consumptionWindowMs = Math.max(1L, consumptionWindowMs);
        this.minStrategySamples = Math.max(1, minStrategySamples);

        if (startWorker) {
            Thread.ofVirtual()
                    .name("prefetch-worker")
                    .start(this::prefetchWorkerLoop);
        }
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
            int budget = effectiveBudget("dag-topo", dagMaxPages);
            if (budget <= 0) {
                return;
            }
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
                    .limit(budget)
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
            int budget = effectiveBudget("semantic-nbhd", semanticMaxPages);
            if (budget <= 0) {
                return;
            }
            Collection<SemanticPage> allPages = pageTable.allPages();

            // Score each page by centroid similarity to the query
            List<ScoredPage> scored = allPages.stream()
                    .filter(p -> p.getState() != PageState.RESIDENT && p.getState() != PageState.FAULTING)
                    .filter(p -> p.getCentroid() != null)
                    .map(p -> new ScoredPage(p, cosineSimilarity(p.getCentroid(), queryEmbedding)))
                    .filter(sp -> sp.similarity >= centroidSimilarityThreshold)
                    .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                    .limit(budget)
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
            int budget = effectiveBudget("branch-spec", branchMaxPagesPerBranch);
            if (budget <= 0) {
                return;
            }
            Set<String> pageIds = pageTable.lookupPagesForDagNode(sourceNodeId);
            pageIds.stream()
                    .map(pageTable::getPage)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(p -> p.getState() != PageState.RESIDENT && p.getState() != PageState.FAULTING)
                    .limit(budget)
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
        return preloadPage(new PrefetchTask(pageId, 0, "manual"));
    }

    private CompletableFuture<Void> preloadPage(PrefetchTask task) {
        String pageId = task.pageId();
        if (!inflightPages.add(pageId)) {
            return CompletableFuture.completedFuture(null); // Already loading
        }
        strategyState(task.source()).recordRequested();
        return pageFaultHandler.preloadPage(pageId)
                .whenComplete((v, ex) -> {
                    inflightPages.remove(pageId);
                    if (ex == null) {
                        prefetchedPages.put(pageId, new PrefetchObservation(task.source(), System.currentTimeMillis()));
                    } else {
                        strategyState(task.source()).recordMiss();
                        log.debug("Prefetch failed for page={}: {}", pageId, ex.getMessage());
                    }
                });
    }

    public void recordFragmentAccess(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) {
            return;
        }
        cleanupExpiredObservations();
        pageTable.lookup(fragmentId).ifPresent(this::recordPageAccess);
    }

    void recordPageAccess(String pageId) {
        if (pageId == null || pageId.isBlank()) {
            return;
        }
        cleanupExpiredObservations();
        PrefetchObservation observation = prefetchedPages.remove(pageId);
        if (observation == null) {
            return;
        }
        if (System.currentTimeMillis() - observation.loadedAtMs() <= consumptionWindowMs) {
            strategyState(observation.source()).recordConsumed();
        } else {
            strategyState(observation.source()).recordMiss();
        }
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
        cleanupExpiredObservations();
        long requested = strategyStates.values().stream().mapToLong(StrategyControlState::requested).sum();
        long hit = strategyStates.values().stream().mapToLong(StrategyControlState::consumed).sum();
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

                preloadPage(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Prefetch worker error: {}", e.getMessage());
            }
        }
    }

    private boolean submitPrefetch(PrefetchTask task) {
        boolean accepted = prefetchQueue.offer(task);
        if (!accepted) {
            log.debug("Prefetch queue at capacity ({}), dropping task: {} priority={}",
                    prefetchQueue.capacity(), task.source(), task.priority());
        }
        return accepted;
    }

    boolean submitPrefetchForTest(String pageId, int priority, String source) {
        return submitPrefetch(new PrefetchTask(pageId, priority, source));
    }

    StrategyStats strategyStatsForTest(String source) {
        cleanupExpiredObservations();
        return strategyState(source).snapshot();
    }

    public StrategySnapshot strategySnapshot(String source) {
        cleanupExpiredObservations();
        StrategyControlState state = strategyState(source);
        StrategyStats stats = state.snapshot();
        return new StrategySnapshot(
                stats.requested(),
                stats.consumed(),
                stats.missed(),
                stats.hitRate(),
                effectiveBudget(source, baseBudgetForSource(source)));
    }

    long queuedTaskCountForTest() {
        return prefetchQueue.size();
    }

    List<String> queuedPageIdsForTest() {
        return prefetchQueue.snapshot().stream().map(PrefetchTask::pageId).toList();
    }

    private int effectiveBudget(String source, int baseBudget) {
        if (baseBudget <= 0) {
            return 0;
        }
        cleanupExpiredObservations();
        StrategyControlState state = strategyState(source);
        long observed = state.observedSamples();
        if (observed < minStrategySamples) {
            return baseBudget;
        }
        double hitRate = state.hitRate();
        if (baseBudget == 1) {
            return hitRate >= 0.10 ? 1 : 0;
        }
        if (hitRate < 0.10) {
            return 1;
        }
        if (hitRate < 0.25) {
            return Math.max(1, (int) Math.ceil(baseBudget * 0.5));
        }
        return baseBudget;
    }

    private int baseBudgetForSource(String source) {
        return switch (source) {
            case "dag-topo" -> dagMaxPages;
            case "semantic-nbhd" -> semanticMaxPages;
            case "branch-spec" -> branchMaxPagesPerBranch;
            default -> 1;
        };
    }

    private void cleanupExpiredObservations() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PrefetchObservation>> iterator = prefetchedPages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PrefetchObservation> entry = iterator.next();
            if (now - entry.getValue().loadedAtMs() > consumptionWindowMs) {
                iterator.remove();
                strategyState(entry.getValue().source()).recordMiss();
            }
        }
    }

    private StrategyControlState strategyState(String source) {
        return strategyStates.computeIfAbsent(source, ignored -> new StrategyControlState());
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

    record StrategyStats(long requested, long consumed, long missed, double hitRate) {}

    public record StrategySnapshot(long requested, long consumed, long missed, double hitRate, int effectiveBudget) {}

    private record PrefetchObservation(String source, long loadedAtMs) {}

    private static final class StrategyControlState {
        private long requested;
        private long consumed;
        private long missed;

        synchronized void recordRequested() {
            requested++;
        }

        synchronized void recordConsumed() {
            consumed++;
        }

        synchronized void recordMiss() {
            missed++;
        }

        synchronized long requested() {
            return requested;
        }

        synchronized long consumed() {
            return consumed;
        }

        synchronized long observedSamples() {
            return consumed + missed;
        }

        synchronized double hitRate() {
            long observed = observedSamples();
            return observed == 0 ? 0.0 : consumed / (double) observed;
        }

        synchronized StrategyStats snapshot() {
            return new StrategyStats(requested, consumed, missed, hitRate());
        }
    }

    private static final class BoundedPrefetchQueue {
        private final int capacity;
        private final PriorityQueue<PrefetchTask> queue = new PriorityQueue<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();

        private BoundedPrefetchQueue(int capacity) {
            this.capacity = capacity;
        }

        boolean offer(PrefetchTask task) {
            lock.lock();
            try {
                if (queue.size() < capacity) {
                    queue.offer(task);
                    notEmpty.signal();
                    return true;
                }
                PrefetchTask lowestPriorityTask = findLowestPriorityTask();
                if (lowestPriorityTask != null && task.priority() > lowestPriorityTask.priority()) {
                    queue.remove(lowestPriorityTask);
                    queue.offer(task);
                    notEmpty.signal();
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        PrefetchTask poll(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            lock.lockInterruptibly();
            try {
                while (queue.isEmpty()) {
                    if (nanos <= 0L) {
                        return null;
                    }
                    nanos = notEmpty.awaitNanos(nanos);
                }
                return queue.poll();
            } finally {
                lock.unlock();
            }
        }

        int size() {
            lock.lock();
            try {
                return queue.size();
            } finally {
                lock.unlock();
            }
        }

        int capacity() {
            return capacity;
        }

        List<PrefetchTask> snapshot() {
            lock.lock();
            try {
                List<PrefetchTask> tasks = new ArrayList<>(queue);
                tasks.sort(null);
                return tasks;
            } finally {
                lock.unlock();
            }
        }

        private PrefetchTask findLowestPriorityTask() {
            PrefetchTask lowest = null;
            for (PrefetchTask candidate : queue) {
                if (lowest == null || candidate.priority() < lowest.priority()) {
                    lowest = candidate;
                }
            }
            return lowest;
        }
    }
}
