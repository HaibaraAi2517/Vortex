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
            case CHECKPOINT_METADATA_LOAD_FAILED,
                 CHECKPOINT_STORAGE_READ_FAILED,
                 CHECKPOINT_VERSION_MISMATCH,
                 FULL_CHECKPOINT_MISSING,
                 FULL_CHECKPOINT_PAYLOAD_INVALID,
                 DELTA_CHECKPOINT_MISSING_BASE,
                 DELTA_CHAIN_BROKEN,
                 DELTA_PAYLOAD_MISSING,
                 DELTA_PAYLOAD_INVALID,
                 BASE_FULL_CHECKPOINT_MISSING,
                 DELTA_STATE_APPLY_FAILED,
                 WAL_STATE_APPLY_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
