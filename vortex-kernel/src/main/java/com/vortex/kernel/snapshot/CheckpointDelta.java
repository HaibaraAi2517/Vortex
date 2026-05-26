package com.vortex.kernel.snapshot;

import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.TaskBranch;
import com.vortex.common.model.TaskState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents an incremental checkpoint payload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointDelta {

    /** The checkpoint this delta builds on. */
    private String baseCheckpointId;

    /** WAL sequence number captured by this checkpoint. */
    private long sequenceNumber;

    /** Nodes that were created or modified since the base checkpoint. */
    private Set<DagNode> changedNodes;

    /** Edges that were created since the base checkpoint. */
    private Set<DagEdge> newEdges;

    /** Context entries that were added or modified since the base checkpoint. */
    private Map<String, String> contextDiff;

    /** Node IDs that were deleted since the base checkpoint. */
    private Set<String> deletedNodeIds;

    /** Active node after applying this delta. */
    private String currentNodeId;

    /** Active branch after applying this delta. */
    private String currentBranchId;

    /** Full branch list snapshot at this checkpoint. */
    private List<TaskBranch> branches;

    /** Task status captured by this checkpoint. */
    private TaskState.TaskStatus status;

    /** Finalization status captured by this checkpoint. */
    private TaskState.TaskFinalizationStatus finalizationStatus;

    public boolean isEmpty() {
        return changedNodes.isEmpty()
                && newEdges.isEmpty()
                && contextDiff.isEmpty()
                && deletedNodeIds.isEmpty();
    }
}
