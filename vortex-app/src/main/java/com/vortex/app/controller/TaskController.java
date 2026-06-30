package com.vortex.app.controller;

import com.vortex.app.runtime.ExecutionIdService;
import com.vortex.common.model.*;
import com.vortex.kernel.snapshot.SnapshotService;
import com.vortex.kernel.snapshot.TaskLifecycleManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Tag(name = "Tasks", description = "Task DAG lifecycle, branching, checkpointing, and recovery APIs")
public class TaskController {

    private final SnapshotService snapshotService;
    private final ExecutionIdService executionIdService;

    // ---- Task Lifecycle ----

    @PostMapping
    @Operation(summary = "Create a task")
    public ResponseEntity<TaskResponseModels.TaskResponse> createTask(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @Valid @RequestBody CreateTaskRequest req) {
        return executionIdService.execute(
                executionId,
                "task.create",
                req,
                () -> ResponseEntity.ok(TaskResponseModels.from(snapshotService.createTask(req.description(), req.namespace()))));
    }

    @GetMapping
    @Operation(summary = "List active tasks with pagination")
    public ResponseEntity<TaskPageResponse> listTasks(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        TaskLifecycleManager.TaskPage result = snapshotService.listActiveTasks(page, size);
        return ResponseEntity.ok(new TaskPageResponse(
                result.items().stream().map(TaskResponseModels::from).toList(),
                result.page(), result.size(), result.total(), result.hasNext()));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "Get a task by ID")
    public ResponseEntity<TaskResponseModels.TaskResponse> getTask(@PathVariable("taskId") String taskId) {
        return snapshotService.getTask(taskId)
                .map(TaskResponseModels::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{taskId}/complete")
    @Operation(summary = "Mark a task as completed")
    public ResponseEntity<Map<String, String>> completeTask(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId) {
        return executionIdService.execute(
                executionId,
                "task.complete",
                request("taskId", taskId),
                () -> {
                    snapshotService.completeTask(taskId);
                    return ResponseEntity.ok(Map.of("taskId", taskId, "status", "COMPLETED"));
                });
    }

    @PostMapping("/{taskId}/fail")
    @Operation(summary = "Mark a task as failed")
    public ResponseEntity<Map<String, String>> failTask(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId) {
        return executionIdService.execute(
                executionId,
                "task.fail",
                request("taskId", taskId),
                () -> {
                    snapshotService.failTask(taskId);
                    return ResponseEntity.ok(Map.of("taskId", taskId, "status", "FAILED"));
                });
    }

    @DeleteMapping("/{taskId}")
    @Operation(summary = "Delete a task and its durable artifacts")
    public ResponseEntity<Map<String, String>> deleteTask(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId) {
        return executionIdService.execute(
                executionId,
                "task.delete",
                request("taskId", taskId),
                () -> {
                    if (!snapshotService.deleteTask(taskId)) {
                        return ResponseEntity.<Map<String, String>>notFound().build();
                    }
                    return ResponseEntity.ok(Map.of("taskId", taskId, "status", "DELETED"));
                });
    }

    // ---- DAG Node Operations ----

    @PostMapping("/{taskId}/nodes")
    @Operation(summary = "Append a DAG node")
    public ResponseEntity<TaskResponseModels.DagNodeResponse> appendNode(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody AppendNodeRequest req) {
        return executionIdService.execute(
                executionId,
                "task.node.append",
                request("taskId", taskId, "body", req),
                () -> {
                    if (req.targetNodeId() != null) {
                        DagEdge.EdgeType edgeType = req.edgeType() != null
                                ? DagEdge.EdgeType.valueOf(req.edgeType().toUpperCase(Locale.ROOT))
                                : DagEdge.EdgeType.CONTROL_DEP;
                        return ResponseEntity.ok(TaskResponseModels.from(snapshotService.appendNodeWithTarget(
                                taskId, req.type(), req.content(), req.targetNodeId(), edgeType)));
                    }
                    return ResponseEntity.ok(TaskResponseModels.from(snapshotService.appendNode(taskId, req.type(), req.content())));
                });
    }

    @PostMapping("/{taskId}/nodes/complete")
    @Operation(summary = "Complete a DAG node")
    public ResponseEntity<TaskResponseModels.DagNodeResponse> completeNode(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody CompleteNodeRequest req) {
        return executionIdService.execute(
                executionId,
                "task.node.complete",
                request("taskId", taskId, "body", req),
                () -> ResponseEntity.ok(TaskResponseModels.from(snapshotService.completeNode(taskId, req.nodeId(), req.result()))));
    }

    @DeleteMapping("/{taskId}/nodes/{nodeId}")
    @Operation(summary = "Delete a DAG node")
    public ResponseEntity<Map<String, String>> deleteNode(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @PathVariable("nodeId") String nodeId) {
        return executionIdService.execute(
                executionId,
                "task.node.delete",
                request("taskId", taskId, "nodeId", nodeId),
                () -> {
                    snapshotService.deleteNode(taskId, nodeId);
                    return ResponseEntity.ok(Map.of("taskId", taskId, "nodeId", nodeId, "status", "DELETED"));
                });
    }

    @PostMapping("/{taskId}/nodes/edge")
    @Operation(summary = "Add an edge between two DAG nodes")
    public ResponseEntity<DagEdge> addEdge(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody AddEdgeRequest req) {
        return executionIdService.execute(
                executionId,
                "task.edge.add",
                request("taskId", taskId, "body", req),
                () -> {
                    DagEdge.EdgeType edgeType = req.dependencyType() != null
                            ? DagEdge.EdgeType.valueOf(req.dependencyType().toUpperCase(Locale.ROOT))
                            : DagEdge.EdgeType.CONTROL_DEP;
                    return ResponseEntity.ok(snapshotService.addEdge(
                            taskId, req.sourceNodeId(), req.targetNodeId(), edgeType, req.condition()));
                });
    }

    @PutMapping("/{taskId}/context")
    @Operation(summary = "Upsert or delete a task context entry")
    public ResponseEntity<Map<String, String>> updateContext(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody UpdateContextRequest req) {
        return executionIdService.execute(
                executionId,
                "task.context.update",
                request("taskId", taskId, "body", req),
                () -> {
                    snapshotService.updateContext(taskId, req.key(), req.value());
                    return ResponseEntity.ok(Map.of("taskId", taskId, "key", req.key()));
                });
    }

    // ---- Checkpoint & Recovery ----

    @PostMapping("/{taskId}/checkpoint")
    @Operation(summary = "Create a checkpoint for a task")
    public ResponseEntity<Map<String, String>> checkpoint(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId) {
        return executionIdService.execute(
                executionId,
                "task.checkpoint",
                request("taskId", taskId),
                () -> {
                    String checkpointId = snapshotService.checkpoint(taskId);
                    return ResponseEntity.ok(Map.of("taskId", taskId, "checkpointId", checkpointId));
                });
    }

    @GetMapping("/{taskId}/checkpoints")
    @Operation(summary = "List checkpoints for a task")
    public ResponseEntity<List<CheckpointMetadata>> listCheckpoints(@PathVariable("taskId") String taskId) {
        return ResponseEntity.ok(snapshotService.listCheckpoints(taskId));
    }

    @PostMapping("/{taskId}/recover")
    @Operation(summary = "Recover a task from a checkpoint or latest durable state")
    public ResponseEntity<TaskResponseModels.TaskResponse> recover(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody(required = false) RecoverRequest body) {
        return executionIdService.execute(
                executionId,
                "task.recover",
                request("taskId", taskId, "body", body),
                () -> ResponseEntity.ok(TaskResponseModels.from(
                        snapshotService.recover(taskId, body != null ? body.checkpointId() : null))));
    }

    // ---- Branching ----

    @GetMapping("/{taskId}/branches")
    @Operation(summary = "List branches for a task")
    public ResponseEntity<List<TaskBranch>> listBranches(@PathVariable("taskId") String taskId) {
        return ResponseEntity.ok(snapshotService.listBranches(taskId));
    }

    @PostMapping("/{taskId}/branch")
    @Operation(summary = "Create a branch from a DAG node")
    public ResponseEntity<TaskBranch> createBranch(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody CreateBranchRequest req) {
        return executionIdService.execute(
                executionId,
                "task.branch.create",
                request("taskId", taskId, "body", req),
                () -> ResponseEntity.ok(snapshotService.createBranch(taskId, req.branchName(), req.sourceNodeId())));
    }

    @PostMapping("/{taskId}/branch/switch")
    @Operation(summary = "Switch the active branch")
    public ResponseEntity<Map<String, String>> switchBranch(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody SwitchBranchRequest req) {
        return executionIdService.execute(
                executionId,
                "task.branch.switch",
                request("taskId", taskId, "body", req),
                () -> {
                    snapshotService.switchBranch(taskId, req.branchId());
                    return ResponseEntity.ok(Map.of("taskId", taskId, "branchId", req.branchId()));
                });
    }

    @PostMapping("/{taskId}/merge")
    @Operation(summary = "Merge a branch into another branch")
    public ResponseEntity<TaskBranch> mergeBranch(
            @RequestHeader(name = ExecutionIdService.HEADER_NAME, required = false) String executionId,
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody MergeBranchRequest req) {
        return executionIdService.execute(
                executionId,
                "task.branch.merge",
                request("taskId", taskId, "body", req),
                () -> ResponseEntity.ok(snapshotService.mergeBranch(
                        taskId, req.sourceBranchId(), req.targetBranchId())));
    }

    // ---- DAG Visualization ----

    @GetMapping(value = "/{taskId}/dag", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Export a task DAG in Graphviz DOT format")
    public ResponseEntity<String> exportDag(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "branchId", required = false) String branchId) {
        String dot = branchId != null
                ? snapshotService.exportDag(taskId, branchId)
                : snapshotService.exportDag(taskId);
        return ResponseEntity.ok(dot);
    }

    // ---- Request DTOs ----

    public record CreateTaskRequest(
            @NotBlank @Size(max = 4_000) String description,
            @NotBlank @Size(max = 128) String namespace) {}

    public record AppendNodeRequest(
            @NotBlank @Size(max = 32) String type,
            @NotBlank @Size(max = 20_000) String content,
            /** Optional: create edge to this existing node. */
            @Size(max = 128) String targetNodeId,
            /** Optional: edge type (CONTROL_DEP, DATA_DEP, BRANCH). Defaults to CONTROL_DEP. */
            @Size(max = 32) String edgeType) {}

    public record CompleteNodeRequest(
            @NotBlank @Size(max = 128) String nodeId,
            @Size(max = 20_000) String result) {}

    public record UpdateContextRequest(
            @NotBlank @Size(max = 128) String key,
            @Size(max = 20_000) String value) {}

    public record AddEdgeRequest(
            @NotBlank @Size(max = 128) String sourceNodeId,
            @NotBlank @Size(max = 128) String targetNodeId,
            @Size(max = 32) String dependencyType,
            @Size(max = 2_000) String condition) {}

    public record RecoverRequest(@Size(max = 128) String checkpointId) {}

    public record CreateBranchRequest(
            @NotBlank @Size(max = 128) String branchName,
            @NotBlank @Size(max = 128) String sourceNodeId) {}

    public record SwitchBranchRequest(@NotBlank @Size(max = 128) String branchId) {}

    public record MergeBranchRequest(
            @NotBlank @Size(max = 128) String sourceBranchId,
            @NotBlank @Size(max = 128) String targetBranchId) {}

    public record TaskPageResponse(
            List<TaskResponseModels.TaskResponse> items,
            int page,
            int size,
            long total,
            boolean hasNext) {}

    private Map<String, Object> request(Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            payload.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return payload;
    }
}
