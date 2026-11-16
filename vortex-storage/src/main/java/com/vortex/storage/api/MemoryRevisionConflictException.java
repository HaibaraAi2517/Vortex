package com.vortex.storage.api;

public class MemoryRevisionConflictException extends RuntimeException {
    private final long currentRevision;

    public MemoryRevisionConflictException(long expected, long current) {
        super("Expected memory revision " + expected + " but current revision is " + current);
        this.currentRevision = current;
    }

    public long getCurrentRevision() { return currentRevision; }
}
