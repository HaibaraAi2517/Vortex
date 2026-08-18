package com.vortex.app.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class ExecutionIdService {

    public static final String HEADER_NAME = "X-Execution-Id";
    public static final String REPLAYED_HEADER_NAME = "X-Execution-Id-Replayed";

    private final ExecutionIdStore store;
    private final ObjectMapper objectMapper;
    private final ExecutionIdProperties properties;

    public <T> ResponseEntity<T> execute(
            String executionId,
            String operation,
            Object request,
            Supplier<ResponseEntity<T>> action) {
        String normalizedExecutionId = normalize(executionId);
        if (normalizedExecutionId == null) {
            return action.get();
        }

        String requestHash = requestHash(operation, request);
        ExecutionIdRecord reservation = ExecutionIdRecord.builder()
                .executionId(normalizedExecutionId)
                .operation(operation)
                .requestHash(requestHash)
                .status(ExecutionIdRecord.Status.IN_PROGRESS)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        if (!store.reserve(reservation, properties.getTtl())) {
            ExecutionIdRecord existing = store.get(normalizedExecutionId)
                    .orElseThrow(() -> new ExecutionIdConflictException(
                            normalizedExecutionId,
                            "Execution ID is reserved but no record is available yet"));
            return replayOrReject(existing, operation, requestHash);
        }

        ResponseEntity<T> response;
        try {
            response = action.get();
        } catch (RuntimeException actionFailure) {
            markUncertain(reservation, "ACTION_FAILED");
            throw actionFailure;
        }

        String responseJson;
        try {
            responseJson = toJson(response.getBody());
        } catch (RuntimeException serializationFailure) {
            markUncertain(reservation, "RESPONSE_SERIALIZATION_FAILED");
            throw new ExecutionIdUncertainException(
                    normalizedExecutionId,
                    "Execution result is uncertain and will not be executed again automatically",
                    serializationFailure);
        }

        ExecutionIdRecord completed = ExecutionIdRecord.builder()
                    .executionId(normalizedExecutionId)
                    .operation(operation)
                    .requestHash(requestHash)
                    .status(ExecutionIdRecord.Status.COMPLETED)
                    .httpStatus(response.getStatusCode().value())
                    .responseJson(responseJson)
                    .createdAt(reservation.getCreatedAt())
                    .updatedAt(Instant.now())
                    .build();
        try {
            if (!store.complete(completed, properties.getTtl())) {
                throw new IllegalStateException("Execution ID state changed before completion");
            }
            return response;
        } catch (RuntimeException completionFailure) {
            markUncertain(reservation, "COMPLETION_PERSIST_FAILED");
            throw new ExecutionIdUncertainException(
                    normalizedExecutionId,
                    "Execution may have completed; automatic retry is blocked",
                    completionFailure);
        }
    }

    private <T> ResponseEntity<T> replayOrReject(
            ExecutionIdRecord record,
            String operation,
            String requestHash) {
        if (!operation.equals(record.getOperation()) || !requestHash.equals(record.getRequestHash())) {
            throw new ExecutionIdConflictException(
                    record.getExecutionId(),
                    "Execution ID has already been used for a different request");
        }
        if (record.getStatus() == ExecutionIdRecord.Status.UNKNOWN) {
            throw new ExecutionIdUncertainException(
                    record.getExecutionId(),
                    "Execution result is uncertain; recover the result or resolve it manually");
        }
        if (record.getStatus() != ExecutionIdRecord.Status.COMPLETED) {
            throw new ExecutionIdConflictException(
                    record.getExecutionId(),
                    "Execution ID is still in progress");
        }
        Object body = fromJson(record.getResponseJson());
        @SuppressWarnings("unchecked")
        T typedBody = (T) body;
        return ResponseEntity.status(record.getHttpStatus())
                .header(REPLAYED_HEADER_NAME, "true")
                .body(typedBody);
    }

    private void markUncertain(ExecutionIdRecord reservation, String failureCode) {
        ExecutionIdRecord uncertain = ExecutionIdRecord.builder()
                .executionId(reservation.getExecutionId())
                .operation(reservation.getOperation())
                .requestHash(reservation.getRequestHash())
                .status(ExecutionIdRecord.Status.UNKNOWN)
                .createdAt(reservation.getCreatedAt())
                .updatedAt(Instant.now())
                .failureCode(failureCode)
                .build();
        try {
            store.markUncertain(uncertain, properties.getTtl());
        } catch (RuntimeException ignored) {
            // Preserve the original failure. The existing IN_PROGRESS reservation still blocks immediate replay.
        }
    }

    private String normalize(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return null;
        }
        String normalized = executionId.trim();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("Invalid execution ID");
        }
        return normalized;
    }

    private String requestHash(String operation, Object request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("request", request == null ? Map.of() : request);
        byte[] json = toJson(payload).getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash execution-id request", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize execution-id payload", e);
        }
    }

    private Object fromJson(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize execution-id response", e);
        }
    }
}
