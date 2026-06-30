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
public class ToolExecutionState {

    private String executionId;
    private String toolName;
    private String input;
    private String output;
    private String errorMessage;

    @Builder.Default
    private ToolExecutionStatus status = ToolExecutionStatus.RUNNING;

    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();

    public void succeed(String output, Instant completedAt) {
        this.output = output;
        this.errorMessage = null;
        this.status = ToolExecutionStatus.SUCCEEDED;
        this.completedAt = completedAt != null ? completedAt : Instant.now();
    }

    public void fail(String errorMessage, Instant completedAt) {
        this.errorMessage = errorMessage;
        this.status = ToolExecutionStatus.FAILED;
        this.completedAt = completedAt != null ? completedAt : Instant.now();
    }
}
