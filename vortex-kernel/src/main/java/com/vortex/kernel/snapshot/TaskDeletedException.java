package com.vortex.kernel.snapshot;

public final class TaskDeletedException extends ResourceNotFoundException {

    public TaskDeletedException(String taskId) {
        super("Task deleted", taskId);
    }
}
