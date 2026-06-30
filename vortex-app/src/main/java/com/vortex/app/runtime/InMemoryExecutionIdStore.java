package com.vortex.app.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryExecutionIdStore implements ExecutionIdStore {

    private final ConcurrentHashMap<String, StoredRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<ExecutionIdRecord> get(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        StoredRecord stored = records.get(executionId);
        if (stored == null) {
            return Optional.empty();
        }
        if (stored.expiresAt().isBefore(Instant.now())) {
            records.remove(executionId, stored);
            return Optional.empty();
        }
        return Optional.of(stored.record());
    }

    @Override
    public boolean reserve(ExecutionIdRecord record, Duration ttl) {
        Duration effectiveTtl = effectiveTtl(ttl);
        StoredRecord stored = new StoredRecord(record, Instant.now().plus(effectiveTtl));
        StoredRecord existing = records.putIfAbsent(record.getExecutionId(), stored);
        if (existing == null) {
            return true;
        }
        if (existing.expiresAt().isBefore(Instant.now())) {
            records.remove(record.getExecutionId(), existing);
            return records.putIfAbsent(record.getExecutionId(), stored) == null;
        }
        return false;
    }

    @Override
    public void complete(ExecutionIdRecord record, Duration ttl) {
        records.put(record.getExecutionId(), new StoredRecord(record, Instant.now().plus(effectiveTtl(ttl))));
    }

    @Override
    public void remove(String executionId) {
        if (executionId != null) {
            records.remove(executionId);
        }
    }

    private Duration effectiveTtl(Duration ttl) {
        return ttl == null || ttl.isNegative() || ttl.isZero()
                ? Duration.ofHours(24)
                : ttl;
    }

    private record StoredRecord(ExecutionIdRecord record, Instant expiresAt) {
    }
}
