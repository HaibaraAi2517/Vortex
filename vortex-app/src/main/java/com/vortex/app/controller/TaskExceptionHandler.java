package com.vortex.app.controller;

import com.vortex.kernel.snapshot.CheckpointRecoveryException;
import com.vortex.kernel.snapshot.CheckpointRecoveryFailureReason;
import com.vortex.kernel.snapshot.InvalidRequestException;
import com.vortex.kernel.snapshot.ResourceNotFoundException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class TaskExceptionHandler {

    @ExceptionHandler(CheckpointRecoveryException.class)
    public ResponseEntity<ProblemDetail> handleCheckpointRecoveryFailure(CheckpointRecoveryException ex) {
        HttpStatus status = mapStatus(ex.getReason());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        detail.setProperty("error", "CHECKPOINT_RECOVERY_FAILED");
        detail.setProperty("reason", ex.getReason().name());
        detail.setProperty("taskId", ex.getTaskId());
        detail.setProperty("checkpointId", ex.getCheckpointId() == null ? "" : ex.getCheckpointId());
        return ResponseEntity.status(status).body(detail);
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

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setProperty("error", "RESOURCE_NOT_FOUND");
        detail.setProperty("resourceType", ex.getResourceType());
        detail.setProperty("resourceId", ex.getResourceId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(detail);
    }

    @ExceptionHandler({InvalidRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> handleInvalidRequest(RuntimeException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage() == null ? "Invalid request" : ex.getMessage());
        detail.setProperty("error", "INVALID_REQUEST");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(detail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of(
                "error", "VALIDATION_FAILED",
                "message", "Request validation failed",
                "fieldErrors", fieldErrors
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "MALFORMED_REQUEST",
                "message", "Request body is malformed or contains invalid enum values"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnhandled(Exception ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage() == null ? "Internal server error" : ex.getMessage());
        detail.setProperty("error", "INTERNAL_SERVER_ERROR");
        return ResponseEntity.status(HttpStatusCode.valueOf(500)).body(detail);
    }
}
