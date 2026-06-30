package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A single immutable entry in the Write-Ahead Log.
 * Written before any in-memory state mutation, enabling deterministic replay on recovery.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionLogEntry {

    /** Monotonic sequence number, per task. Used to determine replay range. */
    private long sequenceNumber;

    /** Unique UUID for idempotent replay. Duplicate entries are skipped during recovery. */
    @Builder.Default
    private String entryId = UUID.randomUUID().toString();

    /** The type of operation this entry records. */
    private OperationType operation;

    /**
     * JSON-serialized payload specific to the operation type.
     * Examples:
     *   APPEND_NODE → {"nodeId":"...","type":"THOUGHT","content":"..."}
     *   COMPLETE_NODE → {"nodeId":"...","result":"..."}
     *   ADD_EDGE → {"sourceNodeId":"...","targetNodeId":"...","type":"DATA_DEP"}
     *   UPDATE_CONTEXT → {"key":"...","value":"..."}
     *   SET_STATUS → {"status":"FAILED"}
     *   APPEND_CONVERSATION_MESSAGE → {"conversationId":"...","messageId":"...","role":"user","content":"..."}
     *   START_TOOL_EXECUTION → {"executionId":"...","toolName":"...","input":"..."}
     *   FAIL_TOOL_EXECUTION → {"executionId":"...","errorMessage":"..."}
     *   TIMEOUT_LLM_CALL → {"callId":"...","errorMessage":"..."}
     */
    private String payload;

    @Builder.Default
    private Instant timestamp = Instant.now();

    public enum OperationType {
        APPEND_NODE,
        COMPLETE_NODE,
        ADD_EDGE,
        DELETE_NODE,
        UPDATE_CONTEXT,
        SET_STATUS,
        CREATE_BRANCH,
        MERGE_BRANCH,
        SWITCH_BRANCH,
        APPEND_CONVERSATION_MESSAGE,
        START_TOOL_EXECUTION,
        COMPLETE_TOOL_EXECUTION,
        FAIL_TOOL_EXECUTION,
        START_LLM_CALL,
        COMPLETE_LLM_CALL,
        TIMEOUT_LLM_CALL,
        MARK_LLM_CALL_RETRY,
        DELETE_TASK
    }
}
