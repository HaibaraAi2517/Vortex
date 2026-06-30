package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmCallState {

    private String callId;
    private String provider;
    private String model;
    private String prompt;
    private String response;
    private String errorMessage;

    @Builder.Default
    private LlmCallStatus status = LlmCallStatus.RUNNING;

    @Builder.Default
    private int attempt = 1;

    private long timeoutMillis;

    @Builder.Default
    private boolean retryable = false;

    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();

    public void complete(String response, Instant completedAt) {
        this.response = response;
        this.errorMessage = null;
        this.status = LlmCallStatus.COMPLETED;
        this.retryable = false;
        this.completedAt = completedAt != null ? completedAt : Instant.now();
    }

    public void timeout(String errorMessage, Instant completedAt) {
        this.errorMessage = errorMessage;
        this.status = LlmCallStatus.TIMED_OUT;
        this.retryable = true;
        this.completedAt = completedAt != null ? completedAt : Instant.now();
    }

    public void markRetryPending() {
        this.status = LlmCallStatus.RETRY_PENDING;
        this.retryable = true;
        this.attempt += 1;
    }
}
