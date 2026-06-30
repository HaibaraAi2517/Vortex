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
                .build();
        if (!store.reserve(reservation, properties.getTtl())) {
            ExecutionIdRecord existing = store.get(normalizedExecutionId)
                    .orElseThrow(() -> new ExecutionIdConflictException(
                            normalizedExecutionId,
                            "Execution ID is reserved but no record is available yet"));
            return replayOrReject(existing, operation, requestHash);
        }

        try {
            ResponseEntity<T> response = action.get();
            store.complete(ExecutionIdRecord.builder()
                    .executionId(normalizedExecutionId)
                    .operation(operation)
                    .requestHash(requestHash)
                    .status(ExecutionIdRecord.Status.COMPLETED)
                    .httpStatus(response.getStatusCode().value())
                    .responseJson(toJson(response.getBody()))
                    .createdAt(reservation.getCreatedAt())
                    .build(), properties.getTtl());
            return response;
        } catch (RuntimeException e) {
            store.remove(normalizedExecutionId);
            throw e;
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
