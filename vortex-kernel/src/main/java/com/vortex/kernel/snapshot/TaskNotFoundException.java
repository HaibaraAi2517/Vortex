package com.vortex.kernel.snapshot;

public final class TaskNotFoundException extends ResourceNotFoundException {

    public TaskNotFoundException(String taskId) {
        super("Task", taskId);
    }
}
