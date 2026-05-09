package com.vortex.app.controller;

import com.vortex.common.model.TaskState;
import com.vortex.kernel.snapshot.SnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final SnapshotService snapshotService;

    /**
     * Create a new task.
     *
     * POST /api/v1/tasks
     * { "description": "...", "namespace": "session-abc" }
     */
    @PostMapping
    public ResponseEntity<TaskState> createTask(@RequestBody CreateTaskRequest req) {
        return ResponseEntity.ok(snapshotService.createTask(req.description(), req.namespace()));
    }

    /**
     * Get current task state.
     *
     * GET /api/v1/tasks/{taskId}
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskState> getTask(@PathVariable String taskId) {
        return snapshotService.getTask(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Append a thought/action node to the task.
     *
     * POST /api/v1/tasks/{taskId}/nodes
     * { "type": "THOUGHT", "content": "..." }
     */
    @PostMapping("/{taskId}/nodes")
    public ResponseEntity<Map<String, String>> appendNode(
            @PathVariable String taskId,
            @RequestBody AppendNodeRequest req) {
        snapshotService.appendNode(taskId, req.type(), req.content());
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "node appended"));
    }

    /**
     * Complete the current node with a result.
     *
     * POST /api/v1/tasks/{taskId}/nodes/complete
     * { "result": "..." }
     */
    @PostMapping("/{taskId}/nodes/complete")
    public ResponseEntity<Map<String, String>> completeNode(
            @PathVariable String taskId,
            @RequestBody Map<String, String> body) {
        snapshotService.completeNode(taskId, body.get("result"));
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "node completed"));
    }

    /**
     * Create a checkpoint for the task.
     *
     * POST /api/v1/tasks/{taskId}/checkpoint
     */
    @PostMapping("/{taskId}/checkpoint")
    public ResponseEntity<Map<String, String>> checkpoint(@PathVariable String taskId) {
        String checkpointId = snapshotService.checkpoint(taskId);
        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "checkpointId", checkpointId
        ));
    }

    /**
     * Recover a task from a checkpoint.
     *
     * POST /api/v1/tasks/{taskId}/recover
     * { "checkpointId": "..." }   (optional — omit to use latest)
     */
    @PostMapping("/{taskId}/recover")
    public ResponseEntity<TaskState> recover(
            @PathVariable String taskId,
            @RequestBody(required = false) Map<String, String> body) {
        String checkpointId = (body != null) ? body.get("checkpointId") : null;
        return ResponseEntity.ok(snapshotService.recover(taskId, checkpointId));
    }

    /**
     * Mark a task as completed.
     *
     * POST /api/v1/tasks/{taskId}/complete
     */
    @PostMapping("/{taskId}/complete")
    public ResponseEntity<Map<String, String>> completeTask(@PathVariable String taskId) {
        snapshotService.completeTask(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "COMPLETED"));
    }

    public record CreateTaskRequest(String description, String namespace) {}
    public record AppendNodeRequest(String type, String content) {}
}
