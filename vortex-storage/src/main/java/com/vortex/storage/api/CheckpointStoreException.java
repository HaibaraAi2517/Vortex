package com.vortex.storage.api;

public class CheckpointStoreException extends IllegalStateException {

    private final FailureType failureType;

    public CheckpointStoreException(FailureType failureType, String message, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public enum FailureType {
        READ_FAILED,
        DELETE_FAILED,
        PAYLOAD_INVALID,
        METADATA_READ_FAILED,
        VERSION_MISMATCH
    }
}
