package com.vortex.kernel.snapshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.ActionLogEntry;
import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.paging.DagChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Handles DAG mutation operations with WAL-before-state pattern.
 *
 * Every mutation is first validated against the current in-memory state, then
 * written to the Write-Ahead Log, then applied to memory. This prevents
 * rejected operations from poisoning recovery.
 */
@Slf4j
@Component
public class DagMutationService {

    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private final ActionLogWriter walWriter;
    private final DirtySetTracker dirtySetTracker;
    private final CheckpointScheduler scheduler;
    private final ApplicationEventPublisher eventPublisher;
    private final BranchManager branchManager;
    private final TaskLifecycleManager taskLifecycleManager;

    public DagMutationService(
            ActionLogWriter walWriter,
            DirtySetTracker dirtySetTracker,
            CheckpointScheduler scheduler,
            ApplicationEventPublisher eventPublisher,
            BranchManager branchManager,
            TaskLifecycleManager taskLifecycleManager) {
        this.walWriter = walWriter;
        this.dirtySetTracker = dirtySetTracker;
        this.scheduler = scheduler;
        this.eventPublisher = eventPublisher;
        this.branchManager = branchManager;
        this.taskLifecycleManager = taskLifecycleManager;
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
                "content", content,
                "branchId", normalizedBranchId(state.getCurrentBranchId()));

        // 1. Write to WAL
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.APPEND_NODE, payload);

        // 2. Apply to in-memory state
        DagNode node = buildPendingNode(nodeId, nodeType, content, entry.getTimestamp(), state.getCurrentBranchId());
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
                "edgeType", edgeType.name(),
                "branchId", normalizedBranchId(state.getCurrentBranchId()));

        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.APPEND_NODE, payload);

        DagNode node = buildPendingNode(nodeId, nodeType, content, entry.getTimestamp(), state.getCurrentBranchId());
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

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private TaskState requireTask(String taskId) {
        return taskLifecycleManager.getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private DagNode requireNode(TaskState state, String nodeId) {
        return state.getGraph().getNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
    }

    private DagNode.NodeType parseNodeType(String type) {
        return DagNode.NodeType.valueOf(type.toUpperCase(Locale.ROOT));
    }

    private DagNode buildPendingNode(
            String nodeId,
            DagNode.NodeType nodeType,
            String content,
            Instant executedAt,
            String branchId) {
        return DagNode.builder()
                .nodeId(nodeId)
                .type(nodeType)
                .content(content)
                .status(DagNode.NodeStatus.PENDING)
                .metadata(BranchManager.branchMetadata(blankToNull(branchId)))
                .executedAt(executedAt)
                .build();
    }

    /**
     * Serializes key-value pairs to a JSON payload string for WAL entries.
     * Package-private so the SnapshotService facade can use it for branching operations.
     */
    String jsonPayload(String... keyValues) {
        if ((keyValues.length & 1) != 0) {
            throw new IllegalArgumentException("jsonPayload requires an even number of key/value arguments");
        }
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

    String normalizedBranchId(String branchId) {
        return branchId == null ? "" : branchId;
    }

    String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
