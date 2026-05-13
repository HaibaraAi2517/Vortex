package com.vortex.app.controller;

import com.vortex.common.model.*;
import com.vortex.kernel.snapshot.SnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final SnapshotService snapshotService;

    // ---- Task Lifecycle ----

    @PostMapping
    public ResponseEntity<TaskState> createTask(@RequestBody CreateTaskRequest req) {
        return ResponseEntity.ok(snapshotService.createTask(req.description(), req.namespace()));
    }

    @GetMapping
    public ResponseEntity<List<TaskState>> listTasks() {
        return ResponseEntity.ok(snapshotService.listActiveTasks());
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskState> getTask(@PathVariable String taskId) {
        return snapshotService.getTask(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{taskId}/complete")
    public ResponseEntity<Map<String, String>> completeTask(@PathVariable String taskId) {
        snapshotService.completeTask(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "COMPLETED"));
    }

    // ---- DAG Node Operations ----

    @PostMapping("/{taskId}/nodes")
    public ResponseEntity<DagNode> appendNode(
            @PathVariable String taskId,
            @RequestBody AppendNodeRequest req) {
        if (req.targetNodeId() != null) {
            DagEdge.EdgeType edgeType = req.edgeType() != null
                    ? DagEdge.EdgeType.valueOf(req.edgeType().toUpperCase())
                    : DagEdge.EdgeType.CONTROL_DEP;
            return ResponseEntity.ok(snapshotService.appendNodeWithTarget(
                    taskId, req.type(), req.content(), req.targetNodeId(), edgeType));
        }
        return ResponseEntity.ok(snapshotService.appendNode(taskId, req.type(), req.content()));
    }

    @PostMapping("/{taskId}/nodes/complete")
    public ResponseEntity<DagNode> completeNode(
            @PathVariable String taskId,
            @RequestBody CompleteNodeRequest req) {
        return ResponseEntity.ok(snapshotService.completeNode(taskId, req.nodeId(), req.result()));
    }

    @PostMapping("/{taskId}/nodes/edge")
    public ResponseEntity<DagEdge> addEdge(
            @PathVariable String taskId,
            @RequestBody AddEdgeRequest req) {
        DagEdge.EdgeType edgeType = req.dependencyType() != null
                ? DagEdge.EdgeType.valueOf(req.dependencyType().toUpperCase())
                : DagEdge.EdgeType.CONTROL_DEP;
        return ResponseEntity.ok(snapshotService.addEdge(
                taskId, req.sourceNodeId(), req.targetNodeId(), edgeType, req.condition()));
    }

    // ---- Checkpoint & Recovery ----

    @PostMapping("/{taskId}/checkpoint")
    public ResponseEntity<Map<String, String>> checkpoint(@PathVariable String taskId) {
        String checkpointId = snapshotService.checkpoint(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "checkpointId", checkpointId));
    }

    @GetMapping("/{taskId}/checkpoints")
    public ResponseEntity<List<CheckpointMetadata>> listCheckpoints(@PathVariable String taskId) {
        return ResponseEntity.ok(snapshotService.listCheckpoints(taskId));
    }

    @PostMapping("/{taskId}/recover")
    public ResponseEntity<TaskState> recover(
            @PathVariable String taskId,
            @RequestBody(required = false) Map<String, String> body) {
        String checkpointId = (body != null) ? body.get("checkpointId") : null;
        return ResponseEntity.ok(snapshotService.recover(taskId, checkpointId));
    }

    // ---- Branching ----

    @GetMapping("/{taskId}/branches")
    public ResponseEntity<List<TaskBranch>> listBranches(@PathVariable String taskId) {
        return ResponseEntity.ok(snapshotService.listBranches(taskId));
    }

    @PostMapping("/{taskId}/branch")
    public ResponseEntity<TaskBranch> createBranch(
            @PathVariable String taskId,
            @RequestBody CreateBranchRequest req) {
        return ResponseEntity.ok(snapshotService.createBranch(taskId, req.branchName(), req.sourceNodeId()));
    }

    @PostMapping("/{taskId}/merge")
    public ResponseEntity<TaskBranch> mergeBranch(
            @PathVariable String taskId,
            @RequestBody MergeBranchRequest req) {
        return ResponseEntity.ok(snapshotService.mergeBranch(
                taskId, req.sourceBranchId(), req.targetBranchId()));
    }

    // ---- DAG Visualization ----

    @GetMapping(value = "/{taskId}/dag", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportDag(
            @PathVariable String taskId,
            @RequestParam(required = false) String branchId) {
        String dot = branchId != null
                ? snapshotService.exportDag(taskId, branchId)
                : snapshotService.exportDag(taskId);
        return ResponseEntity.ok(dot);
    }

    // ---- Request DTOs ----

    public record CreateTaskRequest(String description, String namespace) {}

    public record AppendNodeRequest(
            String type,
            String content,
            /** Optional: create edge to this existing node. */
            String targetNodeId,
            /** Optional: edge type (CONTROL_DEP, DATA_DEP, BRANCH). Defaults to CONTROL_DEP. */
            String edgeType) {}

    public record CompleteNodeRequest(String nodeId, String result) {}

    public record AddEdgeRequest(
            String sourceNodeId,
            String targetNodeId,
            String dependencyType,
            String condition) {}

    public record CreateBranchRequest(String branchName, String sourceNodeId) {}

    public record MergeBranchRequest(String sourceBranchId, String targetBranchId) {}
}
