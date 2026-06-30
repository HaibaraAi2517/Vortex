package com.vortex.app.runtime;

public class ExecutionIdConflictException extends RuntimeException {

    private final String executionId;

    public ExecutionIdConflictException(String executionId, String message) {
        super(message);
        this.executionId = executionId;
    }

    public String getExecutionId() {
        return executionId;
    }
}
