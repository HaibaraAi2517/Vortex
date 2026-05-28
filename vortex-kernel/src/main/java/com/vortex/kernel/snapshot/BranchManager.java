package com.vortex.kernel.snapshot;

import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.TaskBranch;
import com.vortex.common.model.TaskState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages branching and merging of alternative execution paths within a task's DAG.
 *
 * Branches allow exploration of multiple reasoning paths from a single fork point.
 * Only one branch can be "active" (currentBranchId) at a time.
 */
@Slf4j
@Component
public class BranchManager {

    public static final String BRANCH_ID_METADATA_KEY = "branchId";

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
        return createBranch(task, null, branchName, sourceNodeId, null, null);
    }

    public void validateCreateBranch(TaskState task, String sourceNodeId) {
        // Validate source node exists
        Optional<DagNode> sourceNode = task.getGraph().getNode(sourceNodeId);
        if (sourceNode.isEmpty()) {
            throw new InvalidRequestException("Source node not found: " + sourceNodeId);
        }

        // Check branch limit
        long activeBranches = task.getBranches().stream()
                .filter(b -> b.getStatus() == TaskBranch.BranchStatus.ACTIVE)
                .count();
        if (activeBranches >= maxBranchesPerTask) {
            throw new IllegalStateException("Max branches per task reached: " + maxBranchesPerTask);
        }
    }

    public TaskBranch createBranch(TaskState task, String branchId, String branchName, String sourceNodeId) {
        return createBranch(task, branchId, branchName, sourceNodeId, null, null);
    }

    public TaskBranch createBranch(
            TaskState task,
            String branchId,
            String branchName,
            String sourceNodeId,
            String forkNodeId,
            String branchEdgeId) {
        validateCreateBranch(task, sourceNodeId);

        String resolvedBranchId = branchId != null ? branchId : java.util.UUID.randomUUID().toString();
        DagNode forkMarker = DagNode.builder()
                .nodeId(forkNodeId != null ? forkNodeId : java.util.UUID.randomUUID().toString())
                .type(DagNode.NodeType.FORK)
                .content("Fork: " + branchName)
                .status(DagNode.NodeStatus.COMPLETED)
                .metadata(branchMetadata(resolvedBranchId))
                .build();
        task.getGraph().addNode(forkMarker);

        DagEdge branchEdge = DagEdge.builder()
                .edgeId(branchEdgeId != null ? branchEdgeId : java.util.UUID.randomUUID().toString())
                .sourceNodeId(sourceNodeId)
                .targetNodeId(forkMarker.getNodeId())
                .dependencyType(DagEdge.EdgeType.BRANCH)
                .condition(branchName)
                .build();
        task.getGraph().addEdge(branchEdge);

        TaskBranch branch = TaskBranch.builder()
                .branchId(resolvedBranchId)
                .branchName(branchName)
                .sourceNodeId(sourceNodeId)
                .forkNodeId(forkMarker.getNodeId())
                .status(TaskBranch.BranchStatus.ACTIVE)
                .build();

        task.getBranches().add(branch);
        task.setCurrentBranchId(branch.getBranchId());
        task.setCurrentNodeId(forkMarker.getNodeId());

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
    public void validateSwitchBranch(TaskState task, String branchId) {
        boolean exists = task.getBranches().stream()
                .anyMatch(b -> b.getBranchId().equals(branchId)
                        && b.getStatus() == TaskBranch.BranchStatus.ACTIVE);
        if (!exists) {
            throw new InvalidRequestException("Active branch not found: " + branchId);
        }
    }

    public void switchBranch(TaskState task, String branchId) {
        validateSwitchBranch(task, branchId);
        TaskBranch branch = getBranch(task, branchId).orElseThrow();
        task.setCurrentBranchId(branchId);
        task.setCurrentNodeId(resolveBranchCursor(task, branch));
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
    public void validateMergeBranch(TaskState task, String sourceBranchId, String targetBranchId) {
        TaskBranch source = getBranch(task, sourceBranchId)
                .orElseThrow(() -> new BranchNotFoundException(sourceBranchId));
        TaskBranch target = getBranch(task, targetBranchId)
                .orElseThrow(() -> new BranchNotFoundException(targetBranchId));

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
    }

    public TaskBranch mergeBranch(TaskState task, String sourceBranchId, String targetBranchId) {
        validateMergeBranch(task, sourceBranchId, targetBranchId);
        TaskBranch source = getBranch(task, sourceBranchId).orElseThrow();
        TaskBranch target = getBranch(task, targetBranchId).orElseThrow();

        source.markMerged(targetBranchId);
        task.setCurrentBranchId(targetBranchId);

        // Add a MERGE node in the target branch to document the merge
        DagNode mergeNode = DagNode.builder()
                .type(DagNode.NodeType.MERGE)
                .content("Merged branch '" + source.getBranchName() + "' → '" + target.getBranchName() + "'")
                .status(DagNode.NodeStatus.COMPLETED)
                .metadata(branchMetadata(targetBranchId))
                .build();
        task.getGraph().addNode(mergeNode);
        task.setCurrentNodeId(mergeNode.getNodeId());

        log.info("Branch merged: taskId={} source={} target={}",
                task.getTaskId(), sourceBranchId, targetBranchId);

        return target;
    }

    /**
     * Abandon a branch (mark it as ABANDONED without merging).
     */
    public void abandonBranch(TaskState task, String branchId) {
        TaskBranch branch = getBranch(task, branchId)
                .orElseThrow(() -> new BranchNotFoundException(branchId));
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

    public static boolean nodeBelongsToBranch(DagNode node, String branchId) {
        if (node == null || node.getMetadata() == null || branchId == null || branchId.isBlank()) {
            return false;
        }
        return branchId.equals(node.getMetadata().get(BRANCH_ID_METADATA_KEY));
    }

    public static Map<String, String> branchMetadata(String branchId) {
        Map<String, String> metadata = new HashMap<>();
        if (branchId != null && !branchId.isBlank()) {
            metadata.put(BRANCH_ID_METADATA_KEY, branchId);
        }
        return metadata;
    }

    private String resolveBranchCursor(TaskState task, TaskBranch branch) {
        List<DagNode> branchNodes = task.getGraph().getNodes().values().stream()
                .filter(node -> nodeBelongsToBranch(node, branch.getBranchId()))
                .sorted(java.util.Comparator.comparing(
                        DagNode::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        if (!branchNodes.isEmpty()) {
            return branchNodes.getLast().getNodeId();
        }
        if (branch.getForkNodeId() != null && task.getGraph().getNode(branch.getForkNodeId()).isPresent()) {
            return branch.getForkNodeId();
        }
        return branch.getSourceNodeId();
    }
}
