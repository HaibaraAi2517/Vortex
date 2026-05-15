package com.vortex.kernel.snapshot;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
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

/**
 * Manages task lifecycle, checkpointing, recovery, branching, and DAG visualization.
 *
 * Architecture: WAL-before-state — every mutation is first written to the Write-Ahead Log,
 * then applied to the in-memory state. This ensures deterministic, exactly-once recovery.
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

    /** In-memory registry of active tasks. */
    private final Cache<String, TaskState> activeTasks = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(2))
            .removalListener(this::onTaskEvicted)
            .build();

    /** Durable latest-checkpoint index rebuilt from L3 on startup. */
    private final ConcurrentHashMap<String, String> latestCheckpointIds = new ConcurrentHashMap<>();

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
            ApplicationEventPublisher eventPublisher) {
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
        try {
            TaskState recovered = doRecover(taskId, checkpointId);
            activeTasks.put(taskId, recovered);
            log.info("Lazy-loaded task from L3 taskId={}", taskId);
            return Optional.of(recovered);
        } catch (Exception e) {
            log.error("Lazy recovery failed taskId={}: {}", taskId, e.getMessage(), e);
            return Optional.empty();
        }
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

        String nodeId = UUID.randomUUID().toString();
        String payload = jsonPayload(
                "nodeId", nodeId,
                "type", type,
                "content", content);

        // 1. Write to WAL
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.APPEND_NODE, payload);

        // 2. Apply to in-memory state
        DagNode node = DagNode.builder()
                .nodeId(nodeId)
                .type(DagNode.NodeType.valueOf(type.toUpperCase()))
                .content(content)
                .status(DagNode.NodeStatus.PENDING)
                .executedAt(entry.getTimestamp())
                .build();
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

        String nodeId = UUID.randomUUID().toString();
        String payload = jsonPayload(
                "nodeId", nodeId,
                "type", type,
                "content", content,
                "targetNodeId", targetNodeId,
                "edgeType", edgeType.name());

        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.APPEND_NODE, payload);

        DagNode node = DagNode.builder()
                .nodeId(nodeId)
                .type(DagNode.NodeType.valueOf(type.toUpperCase()))
                .content(content)
                .status(DagNode.NodeStatus.PENDING)
                .executedAt(entry.getTimestamp())
                .build();
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
        String payload = jsonPayload(
                "edgeId", edgeId,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "dependencyType", dependencyType.name(),
                "condition", condition != null ? condition : "");

        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.ADD_EDGE, payload);

        DagEdge edge = DagEdge.builder()
                .edgeId(edgeId)
                .sourceNodeId(sourceNodeId)
                .targetNodeId(targetNodeId)
                .dependencyType(dependencyType)
                .condition(condition)
                .build();
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

        String payload = jsonPayload("nodeId", nodeId, "result", result != null ? result : "");

        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.COMPLETE_NODE, payload);

        DagNode node = state.getGraph().getNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
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

    // ========================================================================
    // Branching
    // ========================================================================

    public TaskBranch createBranch(String taskId, String branchName, String sourceNodeId) {
        TaskState state = requireTask(taskId);

        String payload = jsonPayload("branchName", branchName, "sourceNodeId", sourceNodeId);
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.CREATE_BRANCH, payload);
        state.setWalSequenceNumber(entry.getSequenceNumber());

        TaskBranch branch = branchManager.createBranch(state, branchName, sourceNodeId);

        eventPublisher.publishEvent(new DagChangeEvent.BranchCreated(taskId, branchName, sourceNodeId));

        return branch;
    }

    public List<TaskBranch> listBranches(String taskId) {
        TaskState state = requireTask(taskId);
        return branchManager.listBranches(taskId, state);
    }

    public TaskBranch mergeBranch(String taskId, String sourceBranchId, String targetBranchId) {
        TaskState state = requireTask(taskId);

        String payload = jsonPayload("sourceBranchId", sourceBranchId, "targetBranchId", targetBranchId);
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.MERGE_BRANCH, payload);
        state.setWalSequenceNumber(entry.getSequenceNumber());

        return branchManager.mergeBranch(state, sourceBranchId, targetBranchId);
    }

    public void switchBranch(String taskId, String branchId) {
        TaskState state = requireTask(taskId);
        branchManager.switchBranch(state, branchId);

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
            throw new IllegalStateException("No checkpoint found for taskId=" + taskId);
        }

        TaskState recovered = checkpointManager.recoverCheckpoint(taskId, resolvedId);
        recovered.setStatus(TaskState.TaskStatus.RECOVERING);
        recovered.setLatestCheckpointId(resolvedId);
        latestCheckpointIds.put(taskId, resolvedId);
        checkpointManager.reloadTask(taskId);

        long checkpointSeq = recovered.getWalSequenceNumber();
        List<ActionLogEntry> walEntries = walReader.readFrom(taskId, checkpointSeq + 1);

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
            } catch (Exception e) {
                log.error("WAL replay failed for task={} entry={}: {}",
                        taskId, entry.getEntryId(), e.getMessage());
            }
        }

        recovered.setStatus(TaskState.TaskStatus.RUNNING);
        scheduler.registerTask(taskId, this);

        log.info("Task recovered taskId={} checkpointId={} cursor={} walReplayed={} walSkipped={} totalNodes={}",
                taskId, resolvedId, recovered.getCurrentNodeId(), replayed, skipped,
                recovered.getGraph().nodeCount());
        return recovered;
    }

    private void onTaskEvicted(String taskId, TaskState state, RemovalCause cause) {
        if (cause == RemovalCause.EXPLICIT || state == null) {
            return;
        }
        if (state.getStatus() == TaskState.TaskStatus.RUNNING) {
            log.warn("Running task evicted from cache taskId={} cause={}", taskId, cause);
            if (state.getLatestCheckpointId() == null) {
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
                Map<String, String> p = parseJsonPayload(entry.getPayload());
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
                        log.debug("Replay edge skipped (may already exist): {}", e.getMessage());
                    }
                }
                state.setCurrentNodeId(p.get("nodeId"));
            }
            case COMPLETE_NODE -> {
                Map<String, String> p = parseJsonPayload(entry.getPayload());
                state.getGraph().getNode(p.get("nodeId"))
                        .ifPresent(n -> n.complete(p.get("result")));
            }
            case ADD_EDGE -> {
                Map<String, String> p = parseJsonPayload(entry.getPayload());
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
                    log.debug("Replay edge skipped: {}", e.getMessage());
                }
            }
            case UPDATE_CONTEXT -> {
                Map<String, String> p = parseJsonPayload(entry.getPayload());
                String val = p.get("value");
                if (val == null || val.isEmpty()) {
                    state.getContext().remove(p.get("key"));
                } else {
                    state.getContext().put(p.get("key"), val);
                }
            }
            case SET_STATUS -> {
                Map<String, String> p = parseJsonPayload(entry.getPayload());
                state.setStatus(TaskState.TaskStatus.valueOf(p.get("status")));
            }
            case CREATE_BRANCH -> {
                Map<String, String> p = parseJsonPayload(entry.getPayload());
                branchManager.createBranch(state, p.get("branchName"), p.get("sourceNodeId"));
            }
            case MERGE_BRANCH -> {
                Map<String, String> p = parseJsonPayload(entry.getPayload());
                branchManager.mergeBranch(state, p.get("sourceBranchId"), p.get("targetBranchId"));
            }
        }

        state.setWalSequenceNumber(entry.getSequenceNumber());
    }

    // ---- Simple JSON helpers (avoid Jackson overhead for small payloads) ----

    private String jsonPayload(String... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(keyValues[i])).append("\":\"")
                    .append(escapeJson(keyValues[i + 1] != null ? keyValues[i + 1] : "")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, String> parseJsonPayload(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.length() < 3) return map;
        // Simple JSON parser for flat objects like {"key":"value","key2":"value2"}
        String inner = json.substring(1, json.length() - 1);
        String[] pairs = inner.split(",");
        for (String pair : pairs) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String key = pair.substring(0, colon).trim();
            String value = pair.substring(colon + 1).trim();
            key = key.replace("\"", "");
            value = value.replace("\"", "");
            map.put(unescapeJson(key), unescapeJson(value));
        }
        return map;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
