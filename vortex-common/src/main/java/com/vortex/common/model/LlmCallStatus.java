package com.vortex.common.model;

public enum LlmCallStatus {
    RUNNING,
    COMPLETED,
    TIMED_OUT,
    RETRY_PENDING,
    FAILED
}
