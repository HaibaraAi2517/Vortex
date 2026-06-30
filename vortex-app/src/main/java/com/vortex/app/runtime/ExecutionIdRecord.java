package com.vortex.app.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionIdRecord {

    private String executionId;
    private String operation;
    private String requestHash;
    private Status status;
    private int httpStatus;
    private String responseJson;
    private Instant createdAt;

    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }
}
