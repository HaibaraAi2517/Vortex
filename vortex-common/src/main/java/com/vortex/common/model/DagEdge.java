package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A directed edge in the Agent's thought-action DAG.
 * Represents a dependency (data, control, or branch) between two nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DagEdge {

    @Builder.Default
    private String edgeId = UUID.randomUUID().toString();

    /** Source node ID. */
    private String sourceNodeId;

    /** Target node ID. */
    private String targetNodeId;

    @Builder.Default
    private EdgeType dependencyType = EdgeType.CONTROL_DEP;

    /**
     * Optional condition string for conditional edges.
     * E.g., "result > 0" or "status == PASS".
     * Only meaningful for CONTROL_DEP edges.
     */
    private String condition;

    @Builder.Default
    private int weight = 1;

    public enum EdgeType {
        /** Data dependency: target needs data produced by source. */
        DATA_DEP,
        /** Control dependency: target executes only after source completes. */
        CONTROL_DEP,
        /** Branch edge: connects a FORK node to the start of a branch. */
        BRANCH
    }
}
