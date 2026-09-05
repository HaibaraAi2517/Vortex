package com.vortex.kernel.snapshot;

import com.vortex.common.model.ActionLogEntry;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.LlmCallState;
import com.vortex.common.model.TaskState;
import com.vortex.common.model.ToolExecutionState;
import com.vortex.common.serialization.WalPayloads;
import com.vortex.kernel.hmc.MemorySloTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Handles checkpoint recovery with WAL replay and idempotent semantics.
 *
 * Recovery flow:
 *   1. Resolve the target checkpoint ID (from cache, stored index, or L3)
 *   2. Load the checkpoint (FULL or DELTA chain) via {@link IncrementalCheckpointManager}
 *   3. Replay WAL entries from the checkpoint's sequence number
 *   4. Skip already-executed entries (idempotent via entry UUID)
 *   5. Re-register the recovered task with the scheduler
 */
@Slf4j
@Component
public class RecoveryEngine {

    private final ActionLogReader walReader;
    private final ActionLogWriter walWriter;
    private final IncrementalCheckpointManager checkpointManager;
    private final CheckpointRecoveryMetrics checkpointRecoveryMetrics;
    private final MemorySloTracker memorySloTracker;
    private final BranchManager branchManager;
    private final CheckpointScheduler scheduler;

    public RecoveryEngine(
            ActionLogReader walReader,
            ActionLogWriter walWriter,
            IncrementalCheckpointManager checkpointManager,
            CheckpointRecoveryMetrics checkpointRecoveryMetrics,
            MemorySloTracker memorySloTracker,
            BranchManager branchManager,
            CheckpointScheduler scheduler) {
        this.walReader = walReader;
        this.walWriter = walWriter;
        this.checkpointManager = checkpointManager;
        this.checkpointRecoveryMetrics = checkpointRecoveryMetrics;
        this.memorySloTracker = memorySloTracker;
        this.branchManager = branchManager;
        this.scheduler = scheduler;
    }

    /**
     * Recover a task from a checkpoint (or its latest if not specified).
     * The recovered state is cached in the active task registry via {@link TaskLifecycleManager}.
     *
     * @param taskId       the task to recover
     * @param checkpointId optional checkpoint ID; if null, the latest checkpoint is resolved
     * @return the recovered task state
     */
    public TaskState recover(String taskId, String checkpointId, RecoveryStateAccess access) {
        TaskState recovered = doRecover(taskId, checkpointId, access);
        access.attachRecoveredTask(recovered);
        return recovered;
    }

    public TaskState recover(String taskId, String checkpointId) {
        throw new UnsupportedOperationException("RecoveryStateAccess is required for direct RecoveryEngine usage");
    }

    /**
     * Core recovery logic: resolve checkpoint, load state, replay WAL.
     * Package-private so that {@link TaskLifecycleManager} (in the same package)
     * can call it for lazy-loading without re-caching.
     *
     * @param taskId       the task to recover
     * @param checkpointId optional checkpoint ID; if null, auto-resolved
     * @return the recovered task state (not yet cached)
     */
    TaskState doRecover(String taskId, String checkpointId, RecoveryStateAccess access) {
        if (access.isDeleteCommitted(taskId)) {
            throw new TaskDeletedException(taskId);
        }

        String resolvedId = checkpointId;
        if (resolvedId == null) {
            TaskState current = access.getCachedTask(taskId).orElse(null);
            if (current != null && current.getLatestCheckpointId() != null) {
                resolvedId = current.getLatestCheckpointId();
            } else {
                resolvedId = access.getLatestCheckpointId(taskId);
                if (resolvedId == null) {
                    resolvedId = checkpointManager.latestCheckpoint(taskId)
                            .map(CheckpointMetadata::getCheckpointId)
                            .orElse(null);
                }
            }
        }
        if (resolvedId == null) {
            CheckpointRecoveryException failure = new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.NO_CHECKPOINT_AVAILABLE,
                    taskId,
                    checkpointId,
                    "No checkpoint found for taskId=" + taskId);
            recordRecoveryFailure("resolve-checkpoint", failure, null);
            throw failure;
        }

        CheckpointRecoveryResult recovery;
        try {
            recovery = checkpointManager.recoverCheckpoint(taskId, resolvedId);
        } catch (CheckpointRecoveryException e) {
            recordRecoveryFailure("checkpoint-load", e, null);
            throw e;
        }

        TaskState recovered = recovery.state();
        if (!isTerminalStatus(recovered.getStatus())) {
            recovered.setStatus(TaskState.TaskStatus.RECOVERING);
            recovered.setFinalizationStatus(TaskState.TaskFinalizationStatus.NONE);
        } else if (recovered.getFinalizationStatus() == null) {
            recovered.setFinalizationStatus(TaskState.TaskFinalizationStatus.FINALIZED);
        }
        recovered.setLatestCheckpointId(resolvedId);
        if (recovery.checkpointMetadata() != null) {
            recovered.setLastCheckpointAt(recovery.checkpointMetadata().getCreatedAt());
        }
        access.putLatestCheckpointId(taskId, resolvedId);
        checkpointManager.reloadTask(taskId);

        long checkpointSeq = recovered.getWalSequenceNumber();
        List<ActionLogEntry> walEntries;
        try {
            walEntries = walReader.readFrom(taskId, checkpointSeq + 1);
        } catch (CheckpointRecoveryException e) {
            recordRecoveryFailure("wal-read", e, null);
            throw e;
        } catch (Exception e) {
            CheckpointRecoveryException failure = new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    taskId,
                    resolvedId,
                    "Failed to read WAL for taskId=" + taskId + " after checkpointId=" + resolvedId,
                    e);
            recordRecoveryFailure("wal-read", failure, null);
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
                recordRecoveryFailure("wal-replay", e, entry.getEntryId());
                throw e;
            } catch (Exception e) {
                CheckpointRecoveryException failure = new CheckpointRecoveryException(
                        CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                        taskId,
                        resolvedId,
                        "Failed to replay WAL entry taskId=" + taskId + " entryId=" + entry.getEntryId(),
                        e);
                recordRecoveryFailure("wal-replay", failure, entry.getEntryId());
                throw failure;
            }
        }

        if (recovered.getStatus() == TaskState.TaskStatus.RECOVERING) {
            recovered.setStatus(TaskState.TaskStatus.RUNNING);
        }
        if (recovered.getFinalizationStatus() == null) {
            recovered.setFinalizationStatus(isTerminalStatus(recovered.getStatus())
                    ? TaskState.TaskFinalizationStatus.FINALIZED
                    : TaskState.TaskFinalizationStatus.NONE);
        }
        if (!isTerminalStatus(recovered.getStatus())) {
            walWriter.ensureSequenceAtLeast(taskId, recovered.getWalSequenceNumber());
        }
        checkpointRecoveryMetrics.recordSuccess(recovery.mode());
        memorySloTracker.recordCheckpointRecoveryResult(true);

        SnapshotHealthLogSupport.logRecoverySuccess(
                log,
                taskId,
                resolvedId,
                recovery,
                replayed,
                skipped,
                recovered.getGraph().nodeCount(),
                recovered.getCurrentNodeId());
        return recovered;
    }

    // ========================================================================
    // WAL Replay
    // ========================================================================

    /**
     * Replay a single WAL entry onto the recovered state.
     */
    private void replayEntry(TaskState state, ActionLogEntry entry) {
        switch (entry.getOperation()) {
            case APPEND_NODE -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                DagNode node = buildPendingNode(
                        p.get("nodeId"),
                        DagNode.NodeType.valueOf(p.get("type").toUpperCase()),
                        p.get("content"),
                        entry.getTimestamp(),
                        blankToNull(p.get("branchId")));
                state.getGraph().addNode(node);

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
            case DELETE_NODE -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                String nodeId = p.get("nodeId");
                state.getGraph().removeNode(nodeId);
                if (Objects.equals(state.getCurrentNodeId(), nodeId)) {
                    state.setCurrentNodeId(resolveCurrentNodeId(state));
                }
            }
            case DELETE_TASK -> throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    state.getTaskId(),
                    state.getLatestCheckpointId(),
                    "Deleted task must not be recovered taskId=" + state.getTaskId());
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
                String finalizationStatus = p.get("finalizationStatus");
                state.setFinalizationStatus(finalizationStatus == null || finalizationStatus.isBlank()
                        ? inferFinalizationStatusForRecoveredTerminalState(state.getStatus())
                        : TaskState.TaskFinalizationStatus.valueOf(finalizationStatus));
            }
            case CREATE_BRANCH -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                branchManager.createBranch(
                        state,
                        p.get("branchId"),
                        p.get("branchName"),
                        p.get("sourceNodeId"),
                        p.get("forkNodeId"),
                        p.get("branchEdgeId"));
            }
            case MERGE_BRANCH -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                String mergeNodeId = p.get("mergeNodeId");
                if (mergeNodeId == null || mergeNodeId.isBlank()) {
                    // Older WAL records did not carry the ID. Keep replay deterministic.
                    mergeNodeId = java.util.UUID.nameUUIDFromBytes(
                            ("merge:" + entry.getEntryId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                }
                branchManager.mergeBranch(state, p.get("sourceBranchId"), p.get("targetBranchId"),
                        mergeNodeId, entry.getTimestamp());
            }
            case SWITCH_BRANCH -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                branchManager.switchBranch(state, p.get("branchId"));
            }
            case APPEND_CONVERSATION_MESSAGE -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                RuntimeMutationService.appendConversationMessage(
                        state,
                        p.get("conversationId"),
                        RuntimeMutationService.buildConversationMessage(
                                p.get("messageId"),
                                p.get("role"),
                                p.get("content"),
                                entry.getTimestamp()));
            }
            case START_TOOL_EXECUTION -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                state.getToolExecutions().put(p.get("executionId"), RuntimeMutationService.buildToolExecution(
                        p.get("executionId"),
                        p.get("toolName"),
                        p.get("input"),
                        entry.getTimestamp()));
            }
            case COMPLETE_TOOL_EXECUTION -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                requireToolExecution(state, p.get("executionId"), entry).succeed(p.get("output"), entry.getTimestamp());
            }
            case FAIL_TOOL_EXECUTION -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                requireToolExecution(state, p.get("executionId"), entry).fail(p.get("errorMessage"), entry.getTimestamp());
            }
            case START_LLM_CALL -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                state.getLlmCalls().put(p.get("callId"), RuntimeMutationService.buildLlmCall(
                        p.get("callId"),
                        p.get("provider"),
                        p.get("model"),
                        p.get("prompt"),
                        RuntimeMutationService.parseLong(p.get("timeoutMillis")),
                        entry.getTimestamp()));
            }
            case COMPLETE_LLM_CALL -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                requireLlmCall(state, p.get("callId"), entry).complete(p.get("response"), entry.getTimestamp());
            }
            case TIMEOUT_LLM_CALL -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                requireLlmCall(state, p.get("callId"), entry).timeout(p.get("errorMessage"), entry.getTimestamp());
            }
            case MARK_LLM_CALL_RETRY -> {
                Map<String, String> p = parseReplayPayload(state, entry);
                requireLlmCall(state, p.get("callId"), entry).markRetryPending();
            }        }

        state.setWalSequenceNumber(entry.getSequenceNumber());
    }

    // ========================================================================
    // Recovery Internals
    // ========================================================================

    private void recordRecoveryFailure(String phase, CheckpointRecoveryException failure, String entryId) {
        checkpointRecoveryMetrics.recordFailure(failure.getReason());
        memorySloTracker.recordCheckpointRecoveryResult(false);
        SnapshotHealthLogSupport.logRecoveryFailure(
                log,
                phase,
                failure.getTaskId(),
                failure.getCheckpointId(),
                entryId,
                failure.getReason().name(),
                failure);
    }

    private boolean isDuplicateEdge(TaskState state, DagEdge edge) {
        return state.getGraph().containsEquivalentEdge(edge);
    }

    private ToolExecutionState requireToolExecution(TaskState state, String executionId, ActionLogEntry entry) {
        ToolExecutionState toolExecution = state.getToolExecutions().get(executionId);
        if (toolExecution == null) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    state.getTaskId(),
                    state.getLatestCheckpointId(),
                    "Failed to replay tool execution state because execution does not exist taskId="
                            + state.getTaskId() + " executionId=" + executionId + " entryId=" + entry.getEntryId());
        }
        return toolExecution;
    }

    private LlmCallState requireLlmCall(TaskState state, String callId, ActionLogEntry entry) {
        LlmCallState llmCall = state.getLlmCalls().get(callId);
        if (llmCall == null) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    state.getTaskId(),
                    state.getLatestCheckpointId(),
                    "Failed to replay LLM call state because call does not exist taskId="
                            + state.getTaskId() + " callId=" + callId + " entryId=" + entry.getEntryId());
        }
        return llmCall;
    }
    private Map<String, String> parseJsonPayload(String json) {
        if (json == null || json.length() < 3) {
            return new HashMap<>();
        }
        try {
            return WalPayloads.parseStringMap(json);
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
            return WalPayloads.parseStringMap(payload);
        } catch (Exception e) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    state.getTaskId(),
                    state.getLatestCheckpointId(),
                    "Replay payload is invalid taskId=" + state.getTaskId() + " entryId=" + entry.getEntryId(),
                    e);
        }
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean isTerminalStatus(TaskState.TaskStatus status) {
        return status == TaskState.TaskStatus.COMPLETED || status == TaskState.TaskStatus.FAILED;
    }

    private TaskState.TaskFinalizationStatus inferFinalizationStatusForRecoveredTerminalState(TaskState.TaskStatus status) {
        return isTerminalStatus(status)
                ? TaskState.TaskFinalizationStatus.PENDING_FINALIZATION
                : TaskState.TaskFinalizationStatus.NONE;
    }

    private String resolveCurrentNodeId(TaskState state) {
        return state.getGraph().getSinkNodes().stream()
                .max(Comparator.comparing(
                                DagNode::getExecutedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DagNode::getNodeId))
                .map(DagNode::getNodeId)
                .orElse(null);
    }

    // ========================================================================
    // JSON Payload Helper (used by non-recovery callers within same package)
    // ========================================================================

    String jsonPayload(String... keyValues) {
        return WalPayloads.jsonPayload(keyValues);
    }

    interface RecoveryStateAccess {
        boolean isDeleteCommitted(String taskId);

        java.util.Optional<TaskState> getCachedTask(String taskId);

        String getLatestCheckpointId(String taskId);

        void putLatestCheckpointId(String taskId, String checkpointId);

        void attachRecoveredTask(TaskState state);
    }
}
