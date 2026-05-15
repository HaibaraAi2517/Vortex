package com.vortex.app.controller;

import com.vortex.kernel.snapshot.CheckpointRecoveryException;
import com.vortex.kernel.snapshot.CheckpointRecoveryFailureReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class TaskExceptionHandler {

    @ExceptionHandler(CheckpointRecoveryException.class)
    public ResponseEntity<Map<String, Object>> handleCheckpointRecoveryFailure(CheckpointRecoveryException ex) {
        HttpStatus status = mapStatus(ex.getReason());
        return ResponseEntity.status(status).body(Map.of(
                "error", "CHECKPOINT_RECOVERY_FAILED",
                "reason", ex.getReason().name(),
                "message", ex.getMessage(),
                "taskId", ex.getTaskId(),
                "checkpointId", ex.getCheckpointId() == null ? "" : ex.getCheckpointId()
        ));
    }

    private HttpStatus mapStatus(CheckpointRecoveryFailureReason reason) {
        return switch (reason) {
            case NO_CHECKPOINT_AVAILABLE, CHECKPOINT_METADATA_MISSING -> HttpStatus.NOT_FOUND;
            case FULL_CHECKPOINT_MISSING,
                 DELTA_CHECKPOINT_MISSING_BASE,
                 DELTA_CHAIN_BROKEN,
                 DELTA_PAYLOAD_MISSING,
                 DELTA_PAYLOAD_INVALID,
                 BASE_FULL_CHECKPOINT_MISSING -> HttpStatus.CONFLICT;
        };
    }
}
