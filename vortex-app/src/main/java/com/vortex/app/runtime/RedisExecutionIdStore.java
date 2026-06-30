package com.vortex.app.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

@RequiredArgsConstructor
public class RedisExecutionIdStore implements ExecutionIdStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutionIdProperties properties;

    @Override
    public Optional<ExecutionIdRecord> get(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        String payload = redisTemplate.opsForValue().get(key(executionId));
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, ExecutionIdRecord.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read execution-id record from Redis", e);
        }
    }

    @Override
    public boolean reserve(ExecutionIdRecord record, Duration ttl) {
        try {
            Boolean reserved = redisTemplate.opsForValue().setIfAbsent(
                    key(record.getExecutionId()),
                    objectMapper.writeValueAsString(record),
                    effectiveTtl(ttl));
            return Boolean.TRUE.equals(reserved);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to reserve execution-id record in Redis", e);
        }
    }

    @Override
    public void complete(ExecutionIdRecord record, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(
                    key(record.getExecutionId()),
                    objectMapper.writeValueAsString(record),
                    effectiveTtl(ttl));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write execution-id record to Redis", e);
        }
    }

    @Override
    public void remove(String executionId) {
        if (executionId != null && !executionId.isBlank()) {
            redisTemplate.delete(key(executionId));
        }
    }

    private Duration effectiveTtl(Duration ttl) {
        return ttl == null || ttl.isNegative() || ttl.isZero()
                ? Duration.ofHours(24)
                : ttl;
    }

    private String key(String executionId) {
        return properties.getKeyPrefix() + executionId;
    }
}
