package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the full runtime state of a long-running Agent task.
 * Serialised to L3 (MinIO) as a checkpoint.
 */
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

    /** Ordered list of thought/action nodes (the DAG in linear form for MVP). */
    @Builder.Default
    private List<ThoughtNode> nodes = new ArrayList<>();

    /** Index of the node currently being executed. */
    @Builder.Default
    private int cursor = 0;

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
