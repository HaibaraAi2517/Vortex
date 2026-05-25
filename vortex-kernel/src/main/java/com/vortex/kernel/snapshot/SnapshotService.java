package com.vortex.kernel.snapshot;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.*;
import com.vortex.kernel.paging.DagChangeEvent;
import com.vortex.storage.api.L3ColdStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
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
 * Recovery flow:
 *   1. Load most recent FULL checkpoint from L3
 *   2. Apply all subsequent DELTA checkpoints
 *   3. Replay WAL entries from the last checkpoint's sequence number
 *   4. Skip already-executed entries (idempotent via entry UUID)
 *   5. Verify recovered state integrity
 */
@Slf4j
@Service
public class SnapshotService {

    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private final L3ColdStore l3;
    private final ActionLogWriter walWriter;
    private final ActionLogReader walReader;
    private final ActionLogTruncator walTruncator;
    private final IncrementalCheckpointManager checkpointManager;
    private final CheckpointLifecycleManager lifecycleManager;
    private final CheckpointScheduler scheduler;
    private final DirtySetTracker dirtySetTracker;
    private final BranchManager branchManager;
    private final DotGraphExporter dotExporter;
    private final ApplicationEventPublisher eventPublisher;
    private final CheckpointRecoveryMetrics checkpointRecoveryMetrics;

    /** In-memory registry of active tasks. */
    private final Cache<String, TaskState> activeTasks = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(2))
            .removalListener(this::onTaskEvicted)
            .build();

    /** Durable latest-checkpoint index rebuilt from L3 on startup. */
    private final ConcurrentHashMap<String, String> latestCheckpointIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> checkpointLocks = new ConcurrentHashMap<>();

    private final ThreadLocal<Set<String>> evictionCheckpointGuard =
            ThreadLocal.withInitial(HashSet::new);

    public SnapshotService(
            L3ColdStore l3,
            ActionLogWriter walWriter,
            ActionLogReader walReader,
            ActionLogTruncator walTruncator,
            IncrementalCheckpointManager checkpointManager,
            CheckpointLifecycleManager lifecycleManager,
            CheckpointScheduler scheduler,
            DirtySetTracker dirtySetTracker,
            BranchManager branchManager,
            DotGraphExporter dotExporter,
            ApplicationEventPublisher eventPublisher,
            CheckpointRecoveryMetrics checkpointRecoveryMetrics) {
        this.l3 = l3;
        this.walWriter = walWriter;
        this.walReader = walReader;
        this.walTruncator = walTruncator;
        this.checkpointManager = checkpointManager;
        this.lifecycleManager = lifecycleManager;
        this.scheduler = scheduler;
        this.dirtySetTracker = dirtySetTracker;
        this.branchManager = branchManager;
        this.dotExporter = dotExporter;
        this.eventPublisher = eventPublisher;
        this.checkpointRecoveryMetrics = checkpointRecoveryMetrics;
    }

    @PostConstruct
    void rebuildCheckpointIndex() {
        for (String taskId : l3.listTaskIdsWithCheckpoints()) {
            checkpointManager.latestCheckpoint(taskId).ifPresent(meta -> {
                latestCheckpointIds.put(taskId, meta.getCheckpointId());
                log.info("Recovered checkpoint index taskId={} checkpointId={}", taskId, meta.getCheckpointId());
            });
        }
    }

    // ========================================================================
    // Task Lifecycle
    // ========================================================================

    /**
     * Create and register a new task.
     */
    public TaskState createTask(String description, String namespace) {
        String taskId = UUID.randomUUID().toString();
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .description(description)
                .namespace(namespace)
                .graph(new DagGraph())
                .build();
        activeTasks.put(taskId, state);
        scheduler.registerTask(taskId, this);

        log.info("Task created taskId={} namespace={}", taskId, namespace);
        return state;
    }

    /**
     * Get the current state of a task.
     */
    public Optional<TaskState> getTask(String taskId) {
        TaskState cached = activeTasks.getIfPresent(taskId);
        if (cached != null) {
            return Optional.of(cached);
        }

        String checkpointId = latestCheckpointIds.get(taskId);
        if (checkpointId == null) {
            return Optional.empty();
        }
        TaskState recovered = doRecover(taskId, checkpointId);
        activeTasks.put(taskId, recovered);
        log.info("Lazy-loaded task from L3 taskId={}", taskId);
        return Optional.of(recovered);
    }

    /**
     * List all active tasks.
     */
    public List<TaskState> listActiveTasks() {
        return new ArrayList<>(activeTasks.asMap().values());
    }

    // ========================================================================
    // DAG Mutation Operations (WAL-before-state)
    // ========================================================================

    /**
     * Append a node to the task's DAG. Returns the created node.
     */
    public DagNode appendNode(String taskId, String type, String content) {
        TaskState state = requireTask(taskId);
        DagNode.NodeType nodeType = parseNodeType(type);

        String nodeId = UUID.randomUUID().toString();
        String payload = jsonPayload(
                "nodeId", nodeId,
                "type", type,
                "content", content);

        // 1. Write to WAL
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.APPEND_NODE, payload);

        // 2. Apply to in-memory state
        DagNode node = buildPendingNode(nodeId, nodeType, content, entry.getTimestamp());
        state.getGraph().addNode(node);
        state.setCurrentNodeId(nodeId);
        state.setWalSequenceNumber(entry.getSequenceNumber());
        dirtySetTracker.markNodeDirty(taskId, nodeId);
        scheduler.recordAction(taskId);

        eventPublisher.publishEvent(new DagChangeEvent.NodeAppended(taskId, nodeId, type));

        return node;
    }

    /**
     * Append a node and create an edge from a previous node.
     */
    public DagNode appendNodeWithTarget(String taskId, String type, String content,
                                         String targetNodeId, DagEdge.EdgeType edgeType) {
        TaskState state = requireTask(taskId);
        DagNode.NodeType nodeType = parseNodeType(type);
        requireNode(state, targetNodeId);

        String nodeId = UUID.randomUUID().toString();
        String payload = jsonPayload(
                "nodeId", nodeId,
                "type", type,
                "content", content,
                "targetNodeId", targetNodeId,
                "edgeType", edgeType.name());

        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.APPEND_NODE, payload);

        DagNode node = buildPendingNode(nodeId, nodeType, content, entry.getTimestamp());
        state.getGraph().addNode(node);

        DagEdge edge = DagEdge.builder()
                .sourceNodeId(targetNodeId)
                .targetNodeId(nodeId)
                .dependencyType(edgeType)
                .build();
        state.getGraph().addEdge(edge);

        state.setCurrentNodeId(nodeId);
        state.setWalSequenceNumber(entry.getSequenceNumber());
        dirtySetTracker.markNodeDirty(taskId, nodeId);
        dirtySetTracker.markEdgeDirty(taskId, edge.getEdgeId());
        scheduler.recordAction(taskId);

        eventPublisher.publishEvent(new DagChangeEvent.NodeAppended(taskId, nodeId, type));
        eventPublisher.publishEvent(new DagChangeEvent.EdgeAdded(taskId, targetNodeId, nodeId));

        return node;
    }

    /**
     * Add an edge between two existing nodes.
     */
    public DagEdge addEdge(String taskId, String sourceNodeId, String targetNodeId,
                             DagEdge.EdgeType dependencyType, String condition) {
        TaskState state = requireTask(taskId);

        String edgeId = UUID.randomUUID().toString();
        DagEdge edge = DagEdge.builder()
                .edgeId(edgeId)
                .sourceNodeId(sourceNodeId)
                .targetNodeId(targetNodeId)
                .dependencyType(dependencyType)
                .condition(condition)
                .build();
        state.getGraph().validateEdge(edge);

        String payload = jsonPayload(
                "edgeId", edgeId,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "dependencyType", dependencyType.name(),
                "condition", condition != null ? condition : "");

        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.ADD_EDGE, payload);

        state.getGraph().addEdge(edge);
        state.setWalSequenceNumber(entry.getSequenceNumber());
        dirtySetTracker.markEdgeDirty(taskId, edgeId);
        scheduler.recordAction(taskId);

        eventPublisher.publishEvent(new DagChangeEvent.EdgeAdded(taskId, sourceNodeId, targetNodeId));

        return edge;
    }

    /**
     * Complete a node by ID (replaces old cursor-based approach).
     */
    public DagNode completeNode(String taskId, String nodeId, String result) {
        TaskState state = requireTask(taskId);
        DagNode node = requireNode(state, nodeId);

        String payload = jsonPayload("nodeId", nodeId, "result", result != null ? result : "");

        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.COMPLETE_NODE, payload);

        node.complete(result);
        state.setWalSequenceNumber(entry.getSequenceNumber());
        dirtySetTracker.markNodeDirty(taskId, nodeId);
        scheduler.recordAction(taskId);

        eventPublisher.publishEvent(new DagChangeEvent.NodeCompleted(taskId, nodeId, result));

        return node;
    }

    /**
     * Update a context key-value pair.
     */
    public void updateContext(String taskId, String key, String value) {
        TaskState state = requireTask(taskId);

        String payload = jsonPayload("key", key, "value", value != null ? value : "");

        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.UPDATE_CONTEXT, payload);

        if (value == null) {
            state.getContext().remove(key);
        } else {
            state.getContext().put(key, value);
        }
        state.setWalSequenceNumber(entry.getSequenceNumber());
        dirtySetTracker.markContextDirty(taskId, key);
        scheduler.recordAction(taskId);
    }

    /**
     * Mark a task as failed.
     */
    public void failTask(String taskId) {
        TaskState state = requireTask(taskId);
        String payload = jsonPayload("status", TaskState.TaskStatus.FAILED.name());
        walWriter.append(taskId, ActionLogEntry.OperationType.SET_STATUS, payload);
        state.setStatus(TaskState.TaskStatus.FAILED);
        try {
            checkpoint(taskId);
        } catch (Exception e) {
            log.warn("Final checkpoint on failure skipped taskId={}: {}", taskId, e.getMessage());
        }
        walWriter.close(taskId);
        scheduler.unregisterTask(taskId);
        activeTasks.invalidate(taskId);
        log.info("Task failed and cleaned up taskId={}", taskId);
    }

    // ========================================================================
    // Checkpoint & Recovery
    // ========================================================================

    /**
     * Create a checkpoint for the task.
     */
    public String checkpoint(String taskId) {
        TaskState state = requireTask(taskId);
        return checkpoint(taskId, state);
    }

    private String checkpoint(String taskId, TaskState state) {
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
            latestCheckpointIds.put(taskId, meta.getCheckpointId());

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
        TaskState recovered = doRecover(taskId, checkpointId);
        activeTasks.put(taskId, recovered);
        return recovered;
    }

    /** Test-only: evicts task from cache to verify lazy recovery. */
    public void evictFromCacheForTest(String taskId) {
        activeTasks.invalidate(taskId);
    }

    boolean isTaskLoadedForCheckpoint(String taskId) {
        return activeTasks.getIfPresent(taskId) != null;
    }

    // ========================================================================
    // Branching
    // ========================================================================

    public TaskBranch createBranch(String taskId, String branchName, String sourceNodeId) {
        TaskState state = requireTask(taskId);
        branchManager.validateCreateBranch(state, sourceNodeId);
        String branchId = UUID.randomUUID().toString();
        String payload = jsonPayload("branchId", branchId, "branchName", branchName, "sourceNodeId", sourceNodeId);
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.CREATE_BRANCH, payload);
        state.setWalSequenceNumber(entry.getSequenceNumber());

        TaskBranch branch = branchManager.createBranch(state, branchId, branchName, sourceNodeId);
        if (state.getCurrentNodeId() != null) {
            dirtySetTracker.markNodeDirty(taskId, state.getCurrentNodeId());
        }
        dirtySetTracker.markContextDirty(taskId, "__branch_state__");
        scheduler.recordAction(taskId);

        eventPublisher.publishEvent(new DagChangeEvent.BranchCreated(taskId, branchName, sourceNodeId));

        return branch;
    }

    public List<TaskBranch> listBranches(String taskId) {
        TaskState state = requireTask(taskId);
        return branchManager.listBranches(taskId, state);
    }

    public TaskBranch mergeBranch(String taskId, String sourceBranchId, String targetBranchId) {
        TaskState state = requireTask(taskId);
        branchManager.validateMergeBranch(state, sourceBranchId, targetBranchId);

        String payload = jsonPayload("sourceBranchId", sourceBranchId, "targetBranchId", targetBranchId);
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.MERGE_BRANCH, payload);
        state.setWalSequenceNumber(entry.getSequenceNumber());

        TaskBranch merged = branchManager.mergeBranch(state, sourceBranchId, targetBranchId);
        if (state.getCurrentNodeId() != null) {
            dirtySetTracker.markNodeDirty(taskId, state.getCurrentNodeId());
        }
        dirtySetTracker.markContextDirty(taskId, "__branch_state__");
        scheduler.recordAction(taskId);
        return merged;
    }

    public void switchBranch(String taskId, String branchId) {
        TaskState state = requireTask(taskId);
        branchManager.validateSwitchBranch(state, branchId);
        String payload = jsonPayload("branchId", branchId);
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.SWITCH_BRANCH, payload);
        state.setWalSequenceNumber(entry.getSequenceNumber());
        branchManager.switchBranch(state, branchId);
        dirtySetTracker.markContextDirty(taskId, "__branch_state__");
        scheduler.recordAction(taskId);

        eventPublisher.publishEvent(new DagChangeEvent.BranchSwitched(taskId, branchId));
    }

    // ========================================================================
    // Visualization
    // ========================================================================

    public String exportDag(String taskId) {
        TaskState state = requireTask(taskId);
        return dotExporter.export(state.getGraph(), taskId);
    }

    public String exportDag(String taskId, String branchId) {
        TaskState state = requireTask(taskId);
        // For MVP, we export the full graph. Branch-specific export is a future enhancement.
        return dotExporter.export(state.getGraph(), taskId)
                .replace("Task_" + taskId, "Task_" + taskId + "_" + branchId);
    }

    public List<CheckpointMetadata> listCheckpoints(String taskId) {
        return checkpointManager.listCheckpoints(taskId);
    }

    // ========================================================================
    // Task Completion
    // ========================================================================

    public void completeTask(String taskId) {
        TaskState state = requireTask(taskId);

        // Final checkpoint
        checkpoint(taskId);

        // Close WAL
        walWriter.close(taskId);

        // Clean up tracking
        scheduler.unregisterTask(taskId);
        checkpointManager.removeTask(taskId);
        latestCheckpointIds.remove(taskId);

        state.setStatus(TaskState.TaskStatus.COMPLETED);
        activeTasks.invalidate(taskId);

        log.info("Task completed taskId={} totalNodes={}", taskId, state.getGraph().nodeCount());
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private TaskState requireTask(String taskId) {
        return getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private TaskState doRecover(String taskId, String checkpointId) {
        String resolvedId = checkpointId;
        if (resolvedId == null) {
            TaskState current = activeTasks.getIfPresent(taskId);
            if (current != null && current.getLatestCheckpointId() != null) {
                resolvedId = current.getLatestCheckpointId();
            } else {
                resolvedId = latestCheckpointIds.computeIfAbsent(taskId,
                        id -> checkpointManager.latestCheckpoint(id)
                                .map(CheckpointMetadata::getCheckpointId)
                                .orElse(null));
            }
        }
        if (resolvedId == null) {
            CheckpointRecoveryException failure = new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.NO_CHECKPOINT_AVAILABLE,
                    taskId,
                    checkpointId,
                    "No checkpoint found for taskId=" + taskId);
            checkpointRecoveryMetrics.recordFailure(failure.getReason());
            log.warn("Checkpoint recovery failed taskId={} checkpointId={} reason={}",
                    taskId, checkpointId, failure.getReason());
            throw failure;
        }

        CheckpointRecoveryResult recovery;
        try {
            recovery = checkpointManager.recoverCheckpoint(taskId, resolvedId);
        } catch (CheckpointRecoveryException e) {
            checkpointRecoveryMetrics.recordFailure(e.getReason());
            log.warn("Checkpoint recovery failed taskId={} checkpointId={} reason={}",
                    taskId, resolvedId, e.getReason(), e);
            throw e;
        }

        TaskState recovered = recovery.state();
        recovered.setStatus(TaskState.TaskStatus.RECOVERING);
        recovered.setLatestCheckpointId(resolvedId);
        latestCheckpointIds.put(taskId, resolvedId);
        checkpointManager.reloadTask(taskId);

        long checkpointSeq = recovered.getWalSequenceNumber();
        List<ActionLogEntry> walEntries;
        try {
            walEntries = walReader.readFrom(taskId, checkpointSeq + 1);
        } catch (CheckpointRecoveryException e) {
            checkpointRecoveryMetrics.recordFailure(e.getReason());
            log.warn("WAL read failed taskId={} checkpointId={} reason={}",
                    taskId, resolvedId, e.getReason(), e);
            throw e;
        } catch (Exception e) {
            CheckpointRecoveryException failure = new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    taskId,
                    resolvedId,
                    "Failed to read WAL for taskId=" + taskId + " after checkpointId=" + resolvedId,
                    e);
            checkpointRecoveryMetrics.recordFailure(failure.getReason());
            log.warn("WAL read failed taskId={} checkpointId={} reason={}",
                    taskId, resolvedId, failure.getReason(), failure);
            throw failure;
        }

        Set<String> alreadyReplayed = new HashSet<>();

        int replayed = 0;
        int skipped = 0;
        for (ActionLogEntry entry : walEntries) {
            if (!alreadyReplayed.add(entry.getEntryId())) {
                skipped++;
                continue;
            }

            try {
                replayEntry(recovered, entry);
                replayed++;
            } catch (CheckpointRecoveryException e) {
                checkpointRecoveryMetrics.recordFailure(e.getReason());
                log.warn("WAL replay aborted taskId={} checkpointId={} entryId={} reason={}",
                        taskId, resolvedId, entry.getEntryId(), e.getReason(), e);
                throw e;
            } catch (Exception e) {
                CheckpointRecoveryException failure = new CheckpointRecoveryException(
                        CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                        taskId,
                        resolvedId,
                        "Failed to replay WAL entry taskId=" + taskId + " entryId=" + entry.getEntryId(),
                        e);
                checkpointRecoveryMetrics.recordFailure(failure.getReason());
                log.warn("WAL replay aborted taskId={} checkpointId={} entryId={} reason={}",
                        taskId, resolvedId, entry.getEntryId(), failure.getReason(), failure);
                throw failure;
            }
        }

        recovered.setStatus(TaskState.TaskStatus.RUNNING);
        scheduler.registerTask(taskId, this);
        checkpointRecoveryMetrics.recordSuccess(recovery.mode());

        log.info("Task recovered taskId={} checkpointId={} mode={} deltaDepth={} cursor={} walReplayed={} walSkipped={} totalNodes={}",
                taskId, resolvedId, recovery.mode(), recovery.deltaDepth(),
                recovered.getCurrentNodeId(), replayed, skipped, recovered.getGraph().nodeCount());
        return recovered;
    }

    private void onTaskEvicted(String taskId, TaskState state, RemovalCause cause) {
        if (cause == RemovalCause.EXPLICIT || state == null) {
            return;
        }
        if (state.getStatus() == TaskState.TaskStatus.RUNNING) {
            log.warn("Running task evicted from cache taskId={} cause={}", taskId, cause);
            if (state.getLatestCheckpointId() == null || dirtySetTracker.hasDirty(taskId)) {
                Set<String> inProgress = evictionCheckpointGuard.get();
                if (!inProgress.add(taskId)) {
                    log.warn("Skipping recursive emergency checkpoint taskId={}", taskId);
                    return;
                }
                try {
                    checkpoint(taskId, state);
                } catch (Exception e) {
                    log.error("Emergency checkpoint failed on eviction taskId={}", taskId, e);
                } finally {
                    inProgress.remove(taskId);
                    if (inProgress.isEmpty()) {
                        evictionCheckpointGuard.remove();
                    }
                }
            }
        }
    }

    /**
     * Replay a single WAL entry onto the recovered state.
     */
    private void replayEntry(TaskState state, ActionLogEntry entry) {
        switch (entry.getOperation()) {
            case APPEND_NODE -> {
                // Parse payload to reconstruct node
                Map<String, String> p = parseReplayPayload(state, entry);
                DagNode node = DagNode.builder()
                        .nodeId(p.get("nodeId"))
                        .type(DagNode.NodeType.valueOf(p.get("type").toUpperCase()))
                        .content(p.get("content"))
                        .status(DagNode.NodeStatus.PENDING)
                        .executedAt(entry.getTimestamp())
                        .build();
                state.getGraph().addNode(node);

                // If there's a target, add edge too
                if (p.containsKey("targetNodeId") && p.containsKey("edgeType")) {
                    DagEdge edge = DagEdge.builder()
                            .sourceNodeId(p.get("targetNodeId"))
                            .targetNodeId(p.get("nodeId"))
                            .dependencyType(DagEdge.EdgeType.valueOf(p.get("edgeType")))
                            .build();
                    try {
                        state.getGraph().addEdge(edge);
                    } catch (Exception e) {
                        if (isDuplicateEdge(state, edge)) {
                            log.debug("Replay edge skipped because it already exists edgeId={}", edge.getEdgeId());
                        } else {
                            throw new CheckpointRecoveryException(
                                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                                    state.getTaskId(),
                                    state.getLatestCheckpointId(),
                                    "Failed to replay append-node edge taskId=" + state.getTaskId()
                                            + " sourceNodeId=" + edge.getSourceNodeId()
                                            + " targetNodeId=" + edge.getTargetNodeId(),
                                    e);
                        }
                    }
                }
                state.setCurrentNodeId(p.get("nodeId"));
            }
            case COMPLETE_NODE -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                String nodeId = p.get("nodeId");
                state.getGraph().getNode(nodeId)
                        .orElseThrow(() -> new CheckpointRecoveryException(
                                CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                                state.getTaskId(),
                                state.getLatestCheckpointId(),
                                "Failed to replay complete-node because node does not exist taskId="
                                        + state.getTaskId() + " nodeId=" + nodeId))
                        .complete(p.get("result"));
            }
            case ADD_EDGE -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                DagEdge edge = DagEdge.builder()
                        .edgeId(p.get("edgeId"))
                        .sourceNodeId(p.get("sourceNodeId"))
                        .targetNodeId(p.get("targetNodeId"))
                        .dependencyType(DagEdge.EdgeType.valueOf(p.get("dependencyType")))
                        .condition(p.get("condition"))
                        .build();
                try {
                    state.getGraph().addEdge(edge);
                } catch (Exception e) {
                    if (isDuplicateEdge(state, edge)) {
                        log.debug("Replay edge skipped because it already exists edgeId={}", edge.getEdgeId());
                    } else {
                        throw new CheckpointRecoveryException(
                                CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                                state.getTaskId(),
                                state.getLatestCheckpointId(),
                                "Failed to replay edge taskId=" + state.getTaskId() + " edgeId=" + edge.getEdgeId(),
                                e);
                    }
                }
            }
            case UPDATE_CONTEXT -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                String val = p.get("value");
                if (val == null || val.isEmpty()) {
                    state.getContext().remove(p.get("key"));
                } else {
                    state.getContext().put(p.get("key"), val);
                }
            }
            case SET_STATUS -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                state.setStatus(TaskState.TaskStatus.valueOf(p.get("status")));
            }
            case CREATE_BRANCH -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                branchManager.createBranch(state, p.get("branchId"), p.get("branchName"), p.get("sourceNodeId"));
            }
            case MERGE_BRANCH -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                branchManager.mergeBranch(state, p.get("sourceBranchId"), p.get("targetBranchId"));
            }
            case SWITCH_BRANCH -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                branchManager.switchBranch(state, p.get("branchId"));
            }
        }

        state.setWalSequenceNumber(entry.getSequenceNumber());
    }

    private DagNode.NodeType parseNodeType(String type) {
        return DagNode.NodeType.valueOf(type.toUpperCase(Locale.ROOT));
    }

    private DagNode requireNode(TaskState state, String nodeId) {
        return state.getGraph().getNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
    }

    private DagNode buildPendingNode(String nodeId, DagNode.NodeType nodeType, String content, Instant executedAt) {
        return DagNode.builder()
                .nodeId(nodeId)
                .type(nodeType)
                .content(content)
                .status(DagNode.NodeStatus.PENDING)
                .executedAt(executedAt)
                .build();
    }

    private String jsonPayload(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1] != null ? keyValues[i + 1] : "");
        }
        try {
            return PAYLOAD_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize WAL payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseJsonPayload(String json) {
        if (json == null || json.length() < 3) {
            return new HashMap<>();
        }
        try {
            return PAYLOAD_MAPPER.readValue(json, STRING_MAP_TYPE);
        } catch (Exception e) {
            log.error("Failed to parse WAL payload: {}", json, e);
            return new HashMap<>();
        }
    }

    private Map<String, String> parseReplayPayload(TaskState state, ActionLogEntry entry) {
        String payload = entry.getPayload();
        if (payload == null || payload.isBlank()) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    state.getTaskId(),
                    state.getLatestCheckpointId(),
                    "Replay payload is missing taskId=" + state.getTaskId() + " entryId=" + entry.getEntryId());
        }
        try {
            return PAYLOAD_MAPPER.readValue(payload, STRING_MAP_TYPE);
        } catch (Exception e) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    state.getTaskId(),
                    state.getLatestCheckpointId(),
                    "Replay payload is invalid taskId=" + state.getTaskId() + " entryId=" + entry.getEntryId(),
                    e);
        }
    }

    private boolean isDuplicateEdge(TaskState state, DagEdge edge) {
        synchronized (state.getGraph().getEdges()) {
            return state.getGraph().getEdges().stream().anyMatch(existing ->
                    existing.getSourceNodeId().equals(edge.getSourceNodeId())
                            && existing.getTargetNodeId().equals(edge.getTargetNodeId())
                            && existing.getDependencyType() == edge.getDependencyType());
        }
    }
}
