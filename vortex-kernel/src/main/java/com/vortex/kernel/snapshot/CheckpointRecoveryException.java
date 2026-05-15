package com.vortex.kernel.snapshot;

public class CheckpointRecoveryException extends IllegalStateException {

    private final CheckpointRecoveryFailureReason reason;
    private final String taskId;
    private final String checkpointId;

    public CheckpointRecoveryException(
            CheckpointRecoveryFailureReason reason,
            String taskId,
            String checkpointId,
            String message) {
        super(message);
        this.reason = reason;
        this.taskId = taskId;
        this.checkpointId = checkpointId;
    }

    public CheckpointRecoveryException(
            CheckpointRecoveryFailureReason reason,
            String taskId,
            String checkpointId,
            String message,
            Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.taskId = taskId;
        this.checkpointId = checkpointId;
    }

    public CheckpointRecoveryFailureReason getReason() {
        return reason;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getCheckpointId() {
        return checkpointId;
    }
}
