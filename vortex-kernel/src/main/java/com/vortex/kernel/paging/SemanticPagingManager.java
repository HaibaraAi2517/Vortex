package com.vortex.kernel.paging;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.snapshot.SnapshotService;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central coordinator for the semantic paging subsystem.
 *
 * Bridges the page table, page fault handler, prefetch engine, and eviction
 * policy with the existing HMC and SnapshotService.
 *
 * Listens for DAG change events and triggers appropriate prefetch strategies.
 */
@Slf4j
@Service
public class SemanticPagingManager {

    private final SemanticPageTable pageTable;
    private final PageFaultHandler pageFaultHandler;
    private final PrefetchEngine prefetchEngine;
    private final PageEvictionPolicy pageEvictionPolicy;
    private final L1HotStore l1;
    private final L2WarmStore l2;
    private final EmbeddingService embeddingService;
    private final ApplicationEventPublisher eventPublisher;
    private final SnapshotService snapshotService;
    private final ReentrantLock admissionLock = new ReentrantLock();

    private final boolean enabled;
    private final int pageSize;
    private final int initialBuildMaxFragments;

    /** Track which task's current node is associated with which pages for prefetch. */
    private final ConcurrentHashMap<String, String> taskCurrentNode = new ConcurrentHashMap<>();

    public SemanticPagingManager(
            SemanticPageTable pageTable,
            PageFaultHandler pageFaultHandler,
            PrefetchEngine prefetchEngine,
            PageEvictionPolicy pageEvictionPolicy,
            L1HotStore l1,
            L2WarmStore l2,
            @Qualifier("bgeSmallEmbeddingService") EmbeddingService embeddingService,
            ApplicationEventPublisher eventPublisher,
            SnapshotService snapshotService,
            @Value("${vortex.kernel.paging.enabled:true}") boolean enabled,
            @Value("${vortex.kernel.paging.page-size:10}") int pageSize,
            @Value("${vortex.kernel.paging.initial-build.max-fragments:1000}") int initialBuildMaxFragments) {
        this.pageTable = pageTable;
        this.pageFaultHandler = pageFaultHandler;
        this.prefetchEngine = prefetchEngine;
        this.pageEvictionPolicy = pageEvictionPolicy;
        this.l1 = l1;
        this.l2 = l2;
        this.embeddingService = embeddingService;
        this.eventPublisher = eventPublisher;
        this.snapshotService = snapshotService;
        this.enabled = enabled;
        this.pageSize = pageSize;
        this.initialBuildMaxFragments = initialBuildMaxFragments;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("Semantic paging is disabled");
            return;
        }
        log.info("Semantic paging enabled: pageSize={}, initialBuildMaxFragments={}",
                pageSize, initialBuildMaxFragments);
    }

    /**
     * Initialize the page table from existing fragments in L2 (Milvus).
     * Called once on startup, or lazily on first page fault.
     */
    public void buildInitialPages(String namespace) {
        if (!enabled) return;
        try {
            // Gather fragments — start with L1 since it's fastest
            List<MemoryFragment> fragments = new ArrayList<>(l1.getAll(namespace));
            if (fragments.isEmpty()) {
                log.info("No L1 fragments to build pages for namespace={}", namespace);
                return;
            }
            // Limit to avoid OOM on large datasets
            if (fragments.size() > initialBuildMaxFragments) {
                fragments = fragments.subList(0, initialBuildMaxFragments);
            }
            pageTable.buildPagesFromFragments(fragments);
            log.info("Initial page table built: {} fragments → {} pages for namespace={}",
                    fragments.size(), pageTable.allPages().size(), namespace);
        } catch (Exception e) {
            log.warn("Initial page build failed namespace={}: {}", namespace, e.getMessage());
        }
    }

    // ========================================================================
    // Page fault handling
    // ========================================================================

    /**
     * Handle a page fault when a fragment is not found in L1.
     * Loads the entire page containing this fragment into L1.
     */
    public CompletableFuture<SemanticPage> handlePageFault(String fragmentId, String namespace) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }
        return pageFaultHandler.handlePageFault(fragmentId, namespace);
    }

    // ========================================================================
    // Recall integration
    // ========================================================================

    /**
     * Called after a recall to trigger semantic neighborhood prefetch.
     */
    public void onRecall(float[] queryEmbedding) {
        if (!enabled || queryEmbedding == null) return;
        CompletableFuture.runAsync(() -> prefetchEngine.onRecall(queryEmbedding));
    }

    /**
     * Admit an entire page of fragments to L1 atomically.
     * Called by HMC when a page fault is resolved.
     */
    public void admitPage(SemanticPage page, List<MemoryFragment> fragments) {
        if (!enabled || page == null || fragments == null || fragments.isEmpty()) return;

        admissionLock.lock();
        try {
            for (MemoryFragment fragment : fragments) {
                fragment.recordAccess();
                l1.put(fragment);
            }
            page.recordAccess();
            pageTable.markResident(page.getPageId());
            log.debug("Page admitted to L1: pageId={} fragments={}", page.getPageId(), fragments.size());
        } finally {
            admissionLock.unlock();
        }
    }

    // ========================================================================
    // Event listeners
    // ========================================================================

    @EventListener
    public void onNodeAppended(DagChangeEvent.NodeAppended event) {
        if (!enabled) return;
        taskCurrentNode.put(event.taskId(), event.nodeId());
        Optional<TaskState> task = snapshotService.getTask(event.taskId());
        task.ifPresent(state ->
                prefetchEngine.onDagChange(event.taskId(), event.nodeId(), state.getGraph()));
    }

    @EventListener
    public void onNodeCompleted(DagChangeEvent.NodeCompleted event) {
        if (!enabled) return;
        taskCurrentNode.put(event.taskId(), event.nodeId());
        Optional<TaskState> task = snapshotService.getTask(event.taskId());
        task.ifPresent(state ->
                prefetchEngine.onDagChange(event.taskId(), event.nodeId(), state.getGraph()));
    }

    @EventListener
    public void onEdgeAdded(DagChangeEvent.EdgeAdded event) {
        if (!enabled) return;
        Optional<TaskState> task = snapshotService.getTask(event.taskId());
        task.ifPresent(state ->
                prefetchEngine.onDagChange(event.taskId(), event.targetNodeId(), state.getGraph()));
    }

    @EventListener
    public void onBranchCreated(DagChangeEvent.BranchCreated event) {
        if (!enabled) return;
        prefetchEngine.onBranchCreated(event.taskId(), event.branchName(), event.sourceNodeId());
    }

    @EventListener
    public void onBranchSwitched(DagChangeEvent.BranchSwitched event) {
        if (!enabled) return;
        String currentNodeId = taskCurrentNode.get(event.taskId());
        prefetchEngine.onBranchSwitched(event.taskId(), event.branchId(), currentNodeId);
    }

    // ========================================================================
    // Association helpers (called by callers for DAG awareness)
    // ========================================================================

    /**
     * Associate a DAG node with the page containing a given fragment.
     * Called after fragment recall establishes which fragment is relevant.
     */
    public void associateFragmentWithDagNode(String fragmentId, String nodeId) {
        if (!enabled) return;
        pageTable.lookup(fragmentId).ifPresent(pageId ->
                pageTable.associateDagNode(nodeId, pageId));
    }

    // ========================================================================
    // Stats
    // ========================================================================

    public record PagingStats(
            int totalPages,
            int residentPages,
            int evictedPages,
            PrefetchEngine.PrefetchStats prefetchStats
    ) {}

    public PagingStats getStats() {
        Collection<SemanticPage> all = pageTable.allPages();
        int resident = (int) all.stream().filter(p -> p.getState() == PageState.RESIDENT).count();
        int evicted = (int) all.stream().filter(p -> p.getState() == PageState.EVICTED).count();
        return new PagingStats(all.size(), resident, evicted, prefetchEngine.getStats());
    }

    /** For testing / debugging access to page table. */
    public SemanticPageTable getPageTable() {
        return pageTable;
    }

    public PageFaultHandler getPageFaultHandler() {
        return pageFaultHandler;
    }

    public PrefetchEngine getPrefetchEngine() {
        return prefetchEngine;
    }
}
