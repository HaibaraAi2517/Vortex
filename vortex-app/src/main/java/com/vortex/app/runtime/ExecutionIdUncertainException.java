package com.vortex.app.runtime;

public class ExecutionIdUncertainException extends RuntimeException {

    private final String executionId;

    public ExecutionIdUncertainException(String executionId, String message) {
        super(message);
        this.executionId = executionId;
    }

    public ExecutionIdUncertainException(String executionId, String message, Throwable cause) {
        super(message, cause);
        this.executionId = executionId;
    }

    public String getExecutionId() {
        return executionId;
    }
}
