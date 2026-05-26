package com.vortex.kernel.snapshot;

import com.vortex.common.model.*;
import com.vortex.kernel.hmc.MemorySloTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages task lifecycle, checkpointing, recovery, branching, and DAG visualization.
 *
 * Architecture: validate-before-WAL, then WAL-before-state — every mutation is first
 * validated against the current in-memory state, then written to the Write-Ahead Log,
 * then applied to memory. This prevents rejected operations from poisoning recovery.
 *
 * This class is now a facade that delegates to specialized components:
 *   {@link TaskLifecycleManager} — task CRUD, caching, lifecycle transitions
 *   {@link DagMutationService} — DAG mutation operations (WAL-before-state)
 *   {@link RecoveryEngine} — checkpoint recovery with WAL replay
 *   {@link BranchManager} — branching logic (already extracted)
 *   {@link DotGraphExporter} — DOT graph export (already extracted)
 */
@Slf4j
@Service
public class SnapshotService {

    private final TaskLifecycleManager taskLifecycleManager;
    private final DagMutationService dagMutationService;
    private final RecoveryEngine recoveryEngine;
    private final BranchManager branchManager;
    private final DotGraphExporter dotExporter;
    private final ActionLogWriter walWriter;
    private final ActionLogTruncator walTruncator;
    private final IncrementalCheckpointManager checkpointManager;
    private final CheckpointLifecycleManager lifecycleManager;
    private final CheckpointScheduler scheduler;
    private final CheckpointRecoveryMetrics checkpointRecoveryMetrics;
    private final MemorySloTracker memorySloTracker;

    public SnapshotService(
            TaskLifecycleManager taskLifecycleManager,
            DagMutationService dagMutationService,
            RecoveryEngine recoveryEngine,
            BranchManager branchManager,
            DotGraphExporter dotExporter,
            ActionLogWriter walWriter,
            ActionLogTruncator walTruncator,
            IncrementalCheckpointManager checkpointManager,
            CheckpointLifecycleManager lifecycleManager,
            CheckpointScheduler scheduler,
            CheckpointRecoveryMetrics checkpointRecoveryMetrics,
            MemorySloTracker memorySloTracker) {
        this.taskLifecycleManager = taskLifecycleManager;
        this.dagMutationService = dagMutationService;
        this.recoveryEngine = recoveryEngine;
        this.branchManager = branchManager;
        this.dotExporter = dotExporter;
        this.walWriter = walWriter;
        this.walTruncator = walTruncator;
        this.checkpointManager = checkpointManager;
        this.lifecycleManager = lifecycleManager;
        this.scheduler = scheduler;
        this.checkpointRecoveryMetrics = checkpointRecoveryMetrics;
        this.memorySloTracker = memorySloTracker;
    }

    // ========================================================================
    // Task Lifecycle (delegates to TaskLifecycleManager)
    // ========================================================================

    public TaskState createTask(String description, String namespace) {
        return taskLifecycleManager.createTask(description, namespace);
    }

    public Optional<TaskState> getTask(String taskId) {
        return taskLifecycleManager.getTask(taskId);
    }

    public List<TaskState> listActiveTasks() {
        return taskLifecycleManager.listActiveTasks();
    }

    public TaskLifecycleManager.TaskPage listActiveTasks(int page, int size) {
        return taskLifecycleManager.listActiveTasks(page, size);
    }

    public void completeTask(String taskId) {
        taskLifecycleManager.completeTask(taskId);
    }

    public void failTask(String taskId) {
        taskLifecycleManager.failTask(taskId);
    }

    public boolean deleteTask(String taskId) {
        return taskLifecycleManager.deleteTask(taskId);
    }

    boolean isTaskLoadedForCheckpoint(String taskId) {
        return taskLifecycleManager.isTaskLoadedForCheckpoint(taskId);
    }

    // ========================================================================
    // DAG Mutation Operations (delegates to DagMutationService)
    // ========================================================================

    public DagNode appendNode(String taskId, String type, String content) {
        return dagMutationService.appendNode(taskId, type, content);
    }

    public DagNode appendNodeWithTarget(String taskId, String type, String content,
                                         String targetNodeId, DagEdge.EdgeType edgeType) {
        return dagMutationService.appendNodeWithTarget(taskId, type, content, targetNodeId, edgeType);
    }

    public DagEdge addEdge(String taskId, String sourceNodeId, String targetNodeId,
                             DagEdge.EdgeType dependencyType, String condition) {
        return dagMutationService.addEdge(taskId, sourceNodeId, targetNodeId, dependencyType, condition);
    }

    public DagNode completeNode(String taskId, String nodeId, String result) {
        return dagMutationService.completeNode(taskId, nodeId, result);
    }

    public void deleteNode(String taskId, String nodeId) {
        dagMutationService.deleteNode(taskId, nodeId);
    }

    public void updateContext(String taskId, String key, String value) {
        dagMutationService.updateContext(taskId, key, value);
    }

    // ========================================================================
    // Checkpoint & Recovery
    // ========================================================================

    /**
     * Create a checkpoint for the task.
     */
    public String checkpoint(String taskId) {
        TaskState state = taskLifecycleManager.requireTask(taskId);
        return checkpoint(taskId, state);
    }

    String checkpointLoadedTask(String taskId, TaskState state) {
        return checkpoint(taskId, state);
    }

    private String checkpoint(String taskId, TaskState state) {
        ConcurrentHashMap<String, ReentrantLock> checkpointLocks = taskLifecycleManager.getCheckpointLocks();
        ReentrantLock checkpointLock = checkpointLocks.computeIfAbsent(taskId, id -> new ReentrantLock());
        checkpointLock.lock();
        try {
            // Flush WAL to ensure all entries are on disk
            walWriter.flush(taskId);
            walWriter.rotate(taskId);

            long walSeq = walWriter.currentSequenceNumber(taskId);

            // Create checkpoint (full or delta based on auto-detection)
            CheckpointMetadata meta = checkpointManager.createCheckpoint(state, walSeq);

            state.setLatestCheckpointId(meta.getCheckpointId());
            state.setLastCheckpointAt(Instant.now());
            taskLifecycleManager.putLatestCheckpointId(taskId, meta.getCheckpointId());

            // Truncate WAL up to the checkpoint sequence
            walTruncator.truncate(taskId, walSeq);

            // Reset scheduler counters
            scheduler.onCheckpoint(taskId);

            // Apply retention policy periodically
            lifecycleManager.applyRetention(taskId,
                    checkpointManager.listCheckpoints(taskId));
            checkpointManager.reloadTask(taskId);

            log.info("Checkpoint completed taskId={} checkpointId={} type={} seqNo={}",
                    taskId, meta.getCheckpointId(), meta.getType(), walSeq);
            return meta.getCheckpointId();
        } finally {
            checkpointLock.unlock();
        }
    }

    /**
     * Recover a task from its latest checkpoint with exactly-once semantics.
     */
    public TaskState recover(String taskId, String checkpointId) {
        return recoveryEngine.recover(taskId, checkpointId);
    }

    /** Test-only: evicts task from cache to verify lazy recovery. */
    void evictFromCacheForTest(String taskId) {
        taskLifecycleManager.evictFromCacheForTest(taskId);
    }

    // ========================================================================
    // Branching
    // ========================================================================

    public TaskBranch createBranch(String taskId, String branchName, String sourceNodeId) {
        TaskState state = taskLifecycleManager.requireTask(taskId);
        branchManager.validateCreateBranch(state, sourceNodeId);
        String branchId = UUID.randomUUID().toString();
        String forkNodeId = UUID.randomUUID().toString();
        String branchEdgeId = UUID.randomUUID().toString();
        String payload = dagMutationService.jsonPayload(
                "branchId", branchId,
                "branchName", branchName,
                "sourceNodeId", sourceNodeId,
                "forkNodeId", forkNodeId,
                "branchEdgeId", branchEdgeId);
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.CREATE_BRANCH, payload);
        state.setWalSequenceNumber(entry.getSequenceNumber());

        TaskBranch branch = branchManager.createBranch(
                state, branchId, branchName, sourceNodeId, forkNodeId, branchEdgeId);
        scheduler.recordAction(taskId);
        return branch;
    }

    public List<TaskBranch> listBranches(String taskId) {
        TaskState state = taskLifecycleManager.requireTask(taskId);
        return branchManager.listBranches(taskId, state);
    }

    public TaskBranch mergeBranch(String taskId, String sourceBranchId, String targetBranchId) {
        TaskState state = taskLifecycleManager.requireTask(taskId);
        branchManager.validateMergeBranch(state, sourceBranchId, targetBranchId);

        String payload = dagMutationService.jsonPayload("sourceBranchId", sourceBranchId,
                "targetBranchId", targetBranchId);
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.MERGE_BRANCH, payload);
        state.setWalSequenceNumber(entry.getSequenceNumber());

        TaskBranch merged = branchManager.mergeBranch(state, sourceBranchId, targetBranchId);
        scheduler.recordAction(taskId);
        return merged;
    }

    public void switchBranch(String taskId, String branchId) {
        TaskState state = taskLifecycleManager.requireTask(taskId);
        branchManager.validateSwitchBranch(state, branchId);
        String payload = dagMutationService.jsonPayload("branchId", branchId);
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.SWITCH_BRANCH, payload);
        state.setWalSequenceNumber(entry.getSequenceNumber());
        branchManager.switchBranch(state, branchId);
        scheduler.recordAction(taskId);
    }

    // ========================================================================
    // Visualization
    // ========================================================================

    public String exportDag(String taskId) {
        TaskState state = taskLifecycleManager.requireTask(taskId);
        return dotExporter.export(state.getGraph(), taskId);
    }

    public String exportDag(String taskId, String branchId) {
        TaskState state = taskLifecycleManager.requireTask(taskId);
        TaskBranch branch = branchManager.getBranch(state, branchId)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + branchId));
        DagGraph branchGraph = buildBranchGraph(state, branchId, branch);
        return dotExporter.export(
                branchGraph,
                taskId + "_" + branchId,
                "Task: " + taskId + " [branch=" + branch.getBranchName() + "]");
    }

    public List<CheckpointMetadata> listCheckpoints(String taskId) {
        if (taskLifecycleManager.isDeleteCommitted(taskId)) {
            return List.of();
        }
        return checkpointManager.listCheckpoints(taskId);
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private DagGraph buildBranchGraph(TaskState state, String branchId, TaskBranch branch) {
        DagGraph sourceGraph = state.getGraph();
        Set<String> includedNodeIds = new LinkedHashSet<>();
        includeNodeAndAncestors(sourceGraph, branch.getSourceNodeId(), includedNodeIds);
        includeNodeAndAncestors(sourceGraph, branch.getForkNodeId(), includedNodeIds);

        for (DagNode node : sourceGraph.getNodes().values()) {
            if (BranchManager.nodeBelongsToBranch(node, branchId)) {
                includeNodeAndAncestors(sourceGraph, node.getNodeId(), includedNodeIds);
            }
        }

        DagGraph branchGraph = new DagGraph();
        for (String nodeId : includedNodeIds) {
            sourceGraph.getNode(nodeId).ifPresent(branchGraph::addNode);
        }
        synchronized (sourceGraph.getEdges()) {
            for (DagEdge edge : sourceGraph.getEdges()) {
                if (includedNodeIds.contains(edge.getSourceNodeId())
                        && includedNodeIds.contains(edge.getTargetNodeId())) {
                    branchGraph.addEdge(edge);
                }
            }
        }
        return branchGraph;
    }

    private void includeNodeAndAncestors(DagGraph graph, String nodeId, Set<String> includedNodeIds) {
        if (nodeId == null || nodeId.isBlank() || !includedNodeIds.add(nodeId)) {
            return;
        }
        for (DagEdge edge : graph.getIncomingEdges(nodeId)) {
            includeNodeAndAncestors(graph, edge.getSourceNodeId(), includedNodeIds);
        }
    }
}
