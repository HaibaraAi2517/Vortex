package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an alternative execution branch in a task's DAG.
 * Branches allow the Agent to explore parallel or alternative reasoning paths.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskBranch {

    @Builder.Default
    private String branchId = UUID.randomUUID().toString();

    /** Human-readable name for this branch (e.g., "plan-a", "fallback-strategy"). */
    private String branchName;

    /** The DAG node from which this branch was forked. */
    private String sourceNodeId;

    @Builder.Default
    private Instant branchedAt = Instant.now();

    @Builder.Default
    private BranchStatus status = BranchStatus.ACTIVE;

    /** If merged, the branch this one was merged into. */
    private String mergedIntoBranchId;

    /** When the merge occurred. */
    private Instant mergedAt;

    public enum BranchStatus {
        ACTIVE,
        MERGED,
        ABANDONED
    }

    public void markMerged(String targetBranchId) {
        this.status = BranchStatus.MERGED;
        this.mergedIntoBranchId = targetBranchId;
        this.mergedAt = Instant.now();
    }

    public void markAbandoned() {
        this.status = BranchStatus.ABANDONED;
    }
}
