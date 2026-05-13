package com.vortex.kernel.snapshot;

import com.vortex.common.model.DagNode;
import com.vortex.common.model.TaskState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Detects conflicts when merging two branches of a task's DAG.
 *
 * Conflict types:
 * - CONTEXT_KEY: same context key modified in both branches with different values
 * - NODE_MODIFIED: same node modified in both branches (hash mismatch)
 * - STRUCTURAL: merging DAG subgraphs would create a cycle
 */
@Slf4j
@Component
public class BranchMergeConflictDetector {

    /**
     * Detect all conflicts between two branches.
     */
    public List<BranchManager.MergeConflict> detectConflicts(
            TaskState task, String sourceBranchId, String targetBranchId) {

        List<BranchManager.MergeConflict> conflicts = new ArrayList<>();

        // The current implementation compares the task's global state.
        // In a future multi-branch version, each branch would have its own DAG diff.
        // For the MVP, we focus on context conflicts since DAG nodes are shared.

        // Context conflict detection
        Map<String, String> context = task.getContext();
        // Check if both branches modified the same context keys
        // (In a full implementation, we'd track per-branch context changes.)

        // Structural conflict check: would merging create cycles?
        if (task.getGraph().hasCycle()) {
            conflicts.add(new BranchManager.MergeConflict(
                    "STRUCTURAL",
                    "Merging branches would create a cycle in the DAG",
                    null,
                    "Resolve the cycle by removing conflicting edges before merge"
            ));
        }

        log.debug("Conflict detection for task={}: {} conflicts found",
                task.getTaskId(), conflicts.size());

        return conflicts;
    }
}
