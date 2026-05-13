package com.vortex.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;

/**
 * Represents the full runtime state of a long-running Agent task.
 *
 * V2: Uses a true {@link DagGraph} instead of a flat node list.
 * Serialised to L3 (MinIO) as a checkpoint via Kryo binary format.
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskState {

    /** Unique task identifier. */
    private String taskId;

    /** Human-readable task description. */
    private String description;

    /** Current execution status. */
    @Builder.Default
    private TaskStatus status = TaskStatus.RUNNING;

    /** The full DAG of thought/action nodes with edges. */
    @Builder.Default
    @JsonIgnore
    private DagGraph graph = new DagGraph();

    /** Currently active node ID (replaces the old int cursor). */
    private String currentNodeId;

    /** Currently active branch ID. */
    private String currentBranchId;

    /** All branches created during this task's execution. */
    @Builder.Default
    private List<TaskBranch> branches = new ArrayList<>();

    /** Highest WAL sequence number written for this task. */
    @Builder.Default
    private long walSequenceNumber = 0;

    /** IDs of MemoryFragments referenced by this task. */
    @Builder.Default
    private List<String> referencedFragmentIds = new ArrayList<>();

    /** Arbitrary key-value context (e.g., intermediate results, variables). */
    @Builder.Default
    private Map<String, String> context = new HashMap<>();

    /** Namespace / agent session. */
    private String namespace;

    /** When this task was created. */
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** When the last checkpoint was taken. */
    private Instant lastCheckpointAt;

    /** ID of the latest checkpoint object in L3. */
    private String latestCheckpointId;

    public enum TaskStatus {
        RUNNING, PAUSED, COMPLETED, FAILED, RECOVERING
    }

    // ---- Backward compatibility delegates (deprecated, kept for old API consumers) ----

    /**
     * @deprecated Use {@link #graph} and {@link DagGraph#getNode(String)} instead.
     */
    @Deprecated
    public List<ThoughtNode> getNodes() {
        log.warn("TaskState.getNodes() is deprecated — use getGraph() instead. Returning empty list.");
        return Collections.emptyList();
    }

    /**
     * @deprecated Use {@link #graph} and {@link DagGraph#addNode(DagNode)} instead.
     */
    @Deprecated
    public void setNodes(List<ThoughtNode> nodes) {
        log.warn("TaskState.setNodes() is deprecated and no-op. Use getGraph().addNode() instead.");
    }

    /**
     * @deprecated Use {@link #currentNodeId} instead.
     */
    @Deprecated
    public int getCursor() {
        return -1;
    }

    /**
     * @deprecated Use {@link #currentNodeId} instead.
     */
    @Deprecated
    public void setCursor(int cursor) {
        // no-op for backward compat
    }

    // ---- V2 ThoughtNode (still used by deprecated getNodes / fromLegacy) ----

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThoughtNode {
        private String nodeId;
        /** THOUGHT | ACTION | OBSERVATION */
        private String type;
        private String content;
        /** Result of the action, if any. */
        private String result;
        private Instant executedAt;
        @Builder.Default
        private boolean completed = false;
    }
}
