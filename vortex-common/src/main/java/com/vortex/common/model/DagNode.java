package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A single node in the Agent's thought-action DAG.
 * Replaces the MVP flat {@link TaskState.ThoughtNode} with full graph capabilities.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DagNode {

    @Builder.Default
    private String nodeId = UUID.randomUUID().toString();

    /** THOUGHT | ACTION | OBSERVATION | FORK | JOIN | MERGE */
    private NodeType type;

    /** Text content of the thought, action description, or observation result. */
    private String content;

    /** Result produced by an ACTION node. */
    private String result;

    @Builder.Default
    private NodeStatus status = NodeStatus.PENDING;

    /** Arbitrary key-value metadata (e.g., tool name, confidence, cost). */
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant executedAt;

    private Instant completedAt;

    public enum NodeType {
        THOUGHT,
        ACTION,
        OBSERVATION,
        /** Fork into parallel branches. */
        FORK,
        /** Join parallel branches back into one. */
        JOIN,
        /** Merge two alternative branches. */
        MERGE
    }

    public enum NodeStatus {
        PENDING,
        EXECUTING,
        COMPLETED,
        FAILED
    }

    /** Mark this node as completed with a result. */
    public void complete(String result) {
        this.result = result;
        this.status = NodeStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** Mark this node as failed. */
    public void fail(String errorResult) {
        this.result = errorResult;
        this.status = NodeStatus.FAILED;
        this.completedAt = Instant.now();
    }

    /** Mark this node as executing. */
    public void startExecution() {
        this.status = NodeStatus.EXECUTING;
        this.executedAt = Instant.now();
    }

    /** Compute a content hash for conflict detection during branch merges. */
    public int contentHash() {
        int h = 1;
        h = 31 * h + (type != null ? type.hashCode() : 0);
        h = 31 * h + (content != null ? content.hashCode() : 0);
        h = 31 * h + (result != null ? result.hashCode() : 0);
        return h;
    }
}
