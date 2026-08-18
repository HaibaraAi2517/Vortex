package com.vortex.app.controller;

import com.vortex.app.runtime.ExecutionIdConflictException;
import com.vortex.app.runtime.ExecutionIdUncertainException;
import com.vortex.kernel.snapshot.CheckpointRecoveryException;
import com.vortex.kernel.snapshot.CheckpointRecoveryFailureReason;
import com.vortex.kernel.snapshot.InvalidRequestException;
import com.vortex.kernel.snapshot.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
@Slf4j
public class TaskExceptionHandler {

    @ExceptionHandler(CheckpointRecoveryException.class)
    public ResponseEntity<ProblemDetail> handleCheckpointRecoveryFailure(CheckpointRecoveryException ex) {
        HttpStatus status = mapStatus(ex.getReason());
        String correlationId = status.is5xxServerError() ? correlationId(ex) : null;
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                status,
                status.is5xxServerError() ? "Checkpoint recovery failed" : safeMessage(ex.getMessage(), "Checkpoint recovery failed"));
        detail.setProperty("error", "CHECKPOINT_RECOVERY_FAILED");
        detail.setProperty("reason", ex.getReason().name());
        if (!status.is5xxServerError()) {
            detail.setProperty("taskId", ex.getTaskId());
            detail.setProperty("checkpointId", ex.getCheckpointId() == null ? "" : ex.getCheckpointId());
        } else {
            detail.setProperty("correlationId", correlationId);
        }
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

    @ExceptionHandler(ExecutionIdConflictException.class)
    public ResponseEntity<ProblemDetail> handleExecutionIdConflict(ExecutionIdConflictException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setProperty("error", "EXECUTION_ID_CONFLICT");
        detail.setProperty("executionId", ex.getExecutionId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
    }

    @ExceptionHandler(ExecutionIdUncertainException.class)
    public ResponseEntity<ProblemDetail> handleExecutionIdUncertain(ExecutionIdUncertainException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Execution result is uncertain; automatic retry is blocked");
        detail.setProperty("error", "EXECUTION_ID_UNCERTAIN");
        detail.setProperty("executionId", ex.getExecutionId());
        detail.setProperty("state", "UNKNOWN");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(detail);
    }

    @ExceptionHandler({InvalidRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> handleInvalidRequest(RuntimeException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage() == null ? "Invalid request" : ex.getMessage());
        detail.setProperty("error", "INVALID_REQUEST");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(detail);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access is denied");
        detail.setProperty("error", "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(detail);
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

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<Map<String, Object>> handleMethodValidation(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "VALIDATION_FAILED",
                "message", "Request parameter validation failed"
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
        String correlationId = correlationId(ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error");
        detail.setProperty("error", "INTERNAL_SERVER_ERROR");
        detail.setProperty("correlationId", correlationId);
        return ResponseEntity.status(HttpStatusCode.valueOf(500)).body(detail);
    }

    private String correlationId(Exception ex) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Request failed correlationId={}", correlationId, ex);
        return correlationId;
    }

    private String safeMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
