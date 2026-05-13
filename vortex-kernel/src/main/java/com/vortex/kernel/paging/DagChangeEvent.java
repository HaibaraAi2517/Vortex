package com.vortex.kernel.paging;

/**
 * Spring application events published by SnapshotService when the DAG changes.
 * Consumed by {@link SemanticPagingManager} to drive prefetch decisions.
 */
public sealed interface DagChangeEvent {

    String taskId();

    /** A node was appended to the DAG. */
    record NodeAppended(String taskId, String nodeId, String type) implements DagChangeEvent {}

    /** A node was marked completed. */
    record NodeCompleted(String taskId, String nodeId, String result) implements DagChangeEvent {}

    /** An edge was added between two existing nodes. */
    record EdgeAdded(String taskId, String sourceNodeId, String targetNodeId) implements DagChangeEvent {}

    /** A new branch was created from a source node. */
    record BranchCreated(String taskId, String branchName, String sourceNodeId) implements DagChangeEvent {}

    /** Execution switched to a different branch. */
    record BranchSwitched(String taskId, String branchId) implements DagChangeEvent {}
}
