package com.vortex.kernel.snapshot;

import com.vortex.common.model.TaskState;
import com.vortex.storage.api.L3ColdStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages task lifecycle, checkpointing, and recovery.
 *
 * In-memory task registry (ConcurrentHashMap) acts as a fast lookup for
 * running tasks. Checkpoints are persisted to L3 (MinIO) so they survive
 * process restarts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final L3ColdStore l3;

    /** In-memory registry of active tasks. */
    private final ConcurrentHashMap<String, TaskState> activeTasks = new ConcurrentHashMap<>();

    // ---- Task lifecycle ----

    /**
     * Create and register a new task.
     *
     * @param description human-readable description
     * @param namespace   agent/session namespace
     * @return the new TaskState
     */
    public TaskState createTask(String description, String namespace) {
        String taskId = UUID.randomUUID().toString();
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .description(description)
                .namespace(namespace)
                .status(TaskState.TaskStatus.RUNNING)
                .build();
        activeTasks.put(taskId, state);
        log.info("Task created taskId={} namespace={}", taskId, namespace);
        return state;
    }

    /**
     * Append a thought/action node to the task's execution graph.
     */
    public void appendNode(String taskId, String type, String content) {
        TaskState state = requireTask(taskId);
        TaskState.ThoughtNode node = TaskState.ThoughtNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .type(type)
                .content(content)
                .executedAt(Instant.now())
                .build();
        state.getNodes().add(node);
        state.setCursor(state.getNodes().size() - 1);
    }

    /**
     * Mark the current node as completed and record its result.
     */
    public void completeNode(String taskId, String result) {
        TaskState state = requireTask(taskId);
        int cursor = state.getCursor();
        if (cursor >= 0 && cursor < state.getNodes().size()) {
            TaskState.ThoughtNode node = state.getNodes().get(cursor);
            node.setResult(result);
            node.setCompleted(true);
        }
    }

    // ---- Checkpoint / Recovery ----

    /**
     * Persist the current task state to L3 and return the checkpoint ID.
     */
    public String checkpoint(String taskId) {
        TaskState state = requireTask(taskId);
        String checkpointId = l3.saveCheckpoint(state);
        state.setLatestCheckpointId(checkpointId);
        state.setLastCheckpointAt(Instant.now());
        log.info("Checkpoint created taskId={} checkpointId={}", taskId, checkpointId);
        return checkpointId;
    }

    /**
     * Recover a task from its latest checkpoint.
     * The recovered state is re-registered in the active task registry.
     *
     * @param taskId       the task to recover
     * @param checkpointId specific checkpoint to restore (null = latest)
     * @return the recovered TaskState
     */
    public TaskState recover(String taskId, String checkpointId) {
        String resolvedId = checkpointId;
        if (resolvedId == null) {
            // Try to find the latest checkpoint from the active registry
            TaskState current = activeTasks.get(taskId);
            if (current != null && current.getLatestCheckpointId() != null) {
                resolvedId = current.getLatestCheckpointId();
            }
        }
        if (resolvedId == null) {
            throw new IllegalStateException("No checkpoint found for taskId=" + taskId);
        }

        // Build the full L3 key: taskId/checkpointId
        String l3Key = taskId + "/" + resolvedId;
        Optional<TaskState> loaded = l3.loadCheckpoint(l3Key);
        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                    "Checkpoint not found in L3: taskId=" + taskId + " checkpointId=" + resolvedId);
        }

        TaskState recovered = loaded.get();
        recovered.setStatus(TaskState.TaskStatus.RECOVERING);
        activeTasks.put(taskId, recovered);
        log.info("Task recovered taskId={} checkpointId={} cursor={}",
                taskId, resolvedId, recovered.getCursor());
        return recovered;
    }

    /**
     * Mark a task as completed and remove it from the active registry.
     */
    public void completeTask(String taskId) {
        TaskState state = requireTask(taskId);
        state.setStatus(TaskState.TaskStatus.COMPLETED);
        activeTasks.remove(taskId);
        log.info("Task completed taskId={}", taskId);
    }

    /** Get the current state of a task (active or null). */
    public Optional<TaskState> getTask(String taskId) {
        return Optional.ofNullable(activeTasks.get(taskId));
    }

    private TaskState requireTask(String taskId) {
        TaskState state = activeTasks.get(taskId);
        if (state == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return state;
    }
}
