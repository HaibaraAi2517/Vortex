package com.vortex.common.exception;

import com.vortex.common.dto.GenerationLatencyBreakdown;

public class GenerationException extends RuntimeException {

    private final String errorType;
    private final boolean transientError;
    private final GenerationLatencyBreakdown latencyBreakdown;

    public GenerationException(String message) {
        this(message, null, null, false, null);
    }

    public GenerationException(String message, Throwable cause) {
        this(message, cause, null, false, null);
    }

    public GenerationException(
            String message,
            Throwable cause,
            String errorType,
            boolean transientError,
            GenerationLatencyBreakdown latencyBreakdown) {
        super(message, cause);
        this.errorType = errorType;
        this.transientError = transientError;
        this.latencyBreakdown = latencyBreakdown;
    }

    public String getErrorType() {
        return errorType;
    }

    public boolean isTransientError() {
        return transientError;
    }

    public GenerationLatencyBreakdown getLatencyBreakdown() {
        return latencyBreakdown;
    }
}
