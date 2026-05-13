package com.vortex.kernel.snapshot;

import com.vortex.common.model.DagGraph;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.TaskBranch;
import com.vortex.common.model.TaskState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages branching and merging of alternative execution paths within a task's DAG.
 *
 * Branches allow exploration of multiple reasoning paths from a single fork point.
 * Only one branch can be "active" (currentBranchId) at a time.
 */
@Slf4j
@Component
public class BranchManager {

    private final int maxBranchesPerTask;
    private final BranchMergeConflictDetector conflictDetector;

    public BranchManager(
            @Value("${vortex.kernel.snapshot.branch.max-branches-per-task:10}") int maxBranchesPerTask,
            BranchMergeConflictDetector conflictDetector) {
        this.maxBranchesPerTask = maxBranchesPerTask;
        this.conflictDetector = conflictDetector;
    }

    /**
     * Create a new branch from a given DAG node.
     *
     * @param task          the task to branch
     * @param branchName    human-readable name
     * @param sourceNodeId  the node to fork from
     * @return the created branch
     */
    public TaskBranch createBranch(TaskState task, String branchName, String sourceNodeId) {
        // Validate source node exists
        Optional<DagNode> sourceNode = task.getGraph().getNode(sourceNodeId);
        if (sourceNode.isEmpty()) {
            throw new IllegalArgumentException("Source node not found: " + sourceNodeId);
        }

        // Check branch limit
        long activeBranches = task.getBranches().stream()
                .filter(b -> b.getStatus() == TaskBranch.BranchStatus.ACTIVE)
                .count();
        if (activeBranches >= maxBranchesPerTask) {
            throw new IllegalStateException("Max branches per task reached: " + maxBranchesPerTask);
        }

        // Mark source node as FORK type if it isn't already
        DagNode forkNode = sourceNode.get();
        if (forkNode.getType() != DagNode.NodeType.FORK && forkNode.getType() != DagNode.NodeType.JOIN) {
            // Create a separate FORK node
            DagNode forkMarker = DagNode.builder()
                    .type(DagNode.NodeType.FORK)
                    .content("Fork: " + branchName)
                    .status(DagNode.NodeStatus.COMPLETED)
                    .build();
            task.getGraph().addNode(forkMarker);
        }

        TaskBranch branch = TaskBranch.builder()
                .branchName(branchName)
                .sourceNodeId(sourceNodeId)
                .status(TaskBranch.BranchStatus.ACTIVE)
                .build();

        task.getBranches().add(branch);
        task.setCurrentBranchId(branch.getBranchId());

        log.info("Branch created: taskId={} branchId={} branchName={} sourceNodeId={}",
                task.getTaskId(), branch.getBranchId(), branchName, sourceNodeId);

        return branch;
    }

    /**
     * List all branches for a task.
     */
    public List<TaskBranch> listBranches(String taskId, TaskState task) {
        return task.getBranches();
    }

    /**
     * Get a specific branch.
     */
    public Optional<TaskBranch> getBranch(TaskState task, String branchId) {
        return task.getBranches().stream()
                .filter(b -> b.getBranchId().equals(branchId))
                .findFirst();
    }

    /**
     * Switch the active branch.
     */
    public void switchBranch(TaskState task, String branchId) {
        boolean exists = task.getBranches().stream()
                .anyMatch(b -> b.getBranchId().equals(branchId)
                        && b.getStatus() == TaskBranch.BranchStatus.ACTIVE);
        if (!exists) {
            throw new IllegalArgumentException("Active branch not found: " + branchId);
        }

        task.setCurrentBranchId(branchId);
        log.info("Branch switched: taskId={} branchId={}", task.getTaskId(), branchId);
    }

    /**
     * Merge a source branch into a target branch.
     *
     * @param task              the task
     * @param sourceBranchId    branch to merge from
     * @param targetBranchId    branch to merge into
     * @return the merged branch (source is marked MERGED, target is returned)
     */
    public TaskBranch mergeBranch(TaskState task, String sourceBranchId, String targetBranchId) {
        TaskBranch source = getBranch(task, sourceBranchId)
                .orElseThrow(() -> new IllegalArgumentException("Source branch not found: " + sourceBranchId));
        TaskBranch target = getBranch(task, targetBranchId)
                .orElseThrow(() -> new IllegalArgumentException("Target branch not found: " + targetBranchId));

        if (source.getStatus() != TaskBranch.BranchStatus.ACTIVE) {
            throw new IllegalStateException("Source branch is not active: " + sourceBranchId);
        }
        if (target.getStatus() != TaskBranch.BranchStatus.ACTIVE) {
            throw new IllegalStateException("Target branch is not active: " + targetBranchId);
        }

        // Check for conflicts
        List<MergeConflict> conflicts = conflictDetector.detectConflicts(task, sourceBranchId, targetBranchId);
        if (!conflicts.isEmpty()) {
            log.warn("Branch merge has {} conflicts: {}", conflicts.size(),
                    conflicts.stream().map(MergeConflict::description).toList());
            throw new IllegalStateException("Branch merge blocked: " + conflicts.size() + " conflicts detected. "
                    + "Resolve conflicts before merging. Conflicts: " + conflicts);
        }

        source.markMerged(targetBranchId);
        task.setCurrentBranchId(targetBranchId);

        // Add a MERGE node in the target branch to document the merge
        DagNode mergeNode = DagNode.builder()
                .type(DagNode.NodeType.MERGE)
                .content("Merged branch '" + source.getBranchName() + "' → '" + target.getBranchName() + "'")
                .status(DagNode.NodeStatus.COMPLETED)
                .build();
        task.getGraph().addNode(mergeNode);

        log.info("Branch merged: taskId={} source={} target={}",
                task.getTaskId(), sourceBranchId, targetBranchId);

        return target;
    }

    /**
     * Abandon a branch (mark it as ABANDONED without merging).
     */
    public void abandonBranch(TaskState task, String branchId) {
        TaskBranch branch = getBranch(task, branchId)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + branchId));
        branch.markAbandoned();
        log.info("Branch abandoned: taskId={} branchId={}", task.getTaskId(), branchId);

        // If this was the current branch, switch to another active branch or the first one
        if (branchId.equals(task.getCurrentBranchId())) {
            task.getBranches().stream()
                    .filter(b -> b.getStatus() == TaskBranch.BranchStatus.ACTIVE)
                    .findFirst()
                    .ifPresent(b -> task.setCurrentBranchId(b.getBranchId()));
        }
    }

    /**
     * Represents a conflict detected during branch merge.
     */
    public record MergeConflict(
            String conflictType,
            String description,
            String nodeId,
            String suggestedResolution
    ) {}
}
