package com.vortex.app.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Optional;

@RequiredArgsConstructor
public class RedisExecutionIdStore implements ExecutionIdStore {

    private static final DefaultRedisScript<Long> TRANSITION_SCRIPT = new DefaultRedisScript<>("""
            local payload = redis.call('GET', KEYS[1])
            if not payload then return 0 end
            local current = cjson.decode(payload)
            if current.operation ~= ARGV[1] or current.requestHash ~= ARGV[2] then return 0 end
            local allowed = false
            for status in string.gmatch(ARGV[3], '([^,]+)') do
              if current.status == status then allowed = true end
            end
            if not allowed then return 0 end
            redis.call('SET', KEYS[1], ARGV[4], 'PX', ARGV[5])
            return 1
            """, Long.class);

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
                    objectMapper.writeValueAsString(record));
            return Boolean.TRUE.equals(reserved);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to reserve execution-id record in Redis", e);
        }
    }

    @Override
    public boolean complete(ExecutionIdRecord record, Duration ttl) {
        return transition(record, ttl, "IN_PROGRESS,UNKNOWN");
    }

    @Override
    public boolean markUncertain(ExecutionIdRecord record, Duration ttl) {
        return transition(record, ttl, "IN_PROGRESS");
    }

    private boolean transition(ExecutionIdRecord record, Duration ttl, String allowedStatuses) {
        try {
            Duration effectiveTtl = effectiveTtl(ttl);
            Long result = redisTemplate.execute(
                    TRANSITION_SCRIPT,
                    java.util.List.of(key(record.getExecutionId())),
                    record.getOperation(),
                    record.getRequestHash(),
                    allowedStatuses,
                    objectMapper.writeValueAsString(record),
                    Long.toString(effectiveTtl.toMillis()));
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to transition execution-id record in Redis", e);
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
