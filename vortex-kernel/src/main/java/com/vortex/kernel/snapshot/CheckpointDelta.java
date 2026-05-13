package com.vortex.kernel.snapshot;

import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;

import java.util.Map;
import java.util.Set;

/**
 * Represents an incremental (delta) checkpoint.
 *
 * Contains only the changes since the previous checkpoint,
 * rather than a full snapshot of the entire task state.
 *
 * A delta is always applied on top of a base checkpoint (FULL or another DELTA).
 */
public record CheckpointDelta(
        /** The checkpoint this delta builds on. */
        String baseCheckpointId,

        /** WAL sequence number at the time of the base checkpoint. */
        long baseSequenceNumber,

        /** Nodes that were created or modified since the base checkpoint. */
        Set<DagNode> changedNodes,

        /** Edges that were created since the base checkpoint. */
        Set<DagEdge> newEdges,

        /** Context entries that were added or modified since the base checkpoint.
         *  Entries with a null value have been deleted. */
        Map<String, String> contextDiff,

        /** Node IDs that were deleted since the base checkpoint. */
        Set<String> deletedNodeIds
) {
    public boolean isEmpty() {
        return changedNodes.isEmpty() && newEdges.isEmpty() && contextDiff.isEmpty() && deletedNodeIds.isEmpty();
    }
}
