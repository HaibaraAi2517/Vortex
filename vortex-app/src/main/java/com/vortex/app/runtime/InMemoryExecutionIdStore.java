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
        if (isExpired(stored)) {
            records.remove(executionId, stored);
            return Optional.empty();
        }
        return Optional.of(stored.record());
    }

    @Override
    public boolean reserve(ExecutionIdRecord record, Duration ttl) {
        StoredRecord stored = new StoredRecord(record, null);
        StoredRecord existing = records.putIfAbsent(record.getExecutionId(), stored);
        if (existing == null) {
            return true;
        }
        if (isExpired(existing)) {
            records.remove(record.getExecutionId(), existing);
            return records.putIfAbsent(record.getExecutionId(), stored) == null;
        }
        return false;
    }

    @Override
    public boolean complete(ExecutionIdRecord record, Duration ttl) {
        return transition(record, ttl, ExecutionIdRecord.Status.IN_PROGRESS, ExecutionIdRecord.Status.UNKNOWN);
    }

    @Override
    public boolean markUncertain(ExecutionIdRecord record, Duration ttl) {
        return transition(record, ttl, ExecutionIdRecord.Status.IN_PROGRESS);
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

    private boolean isExpired(StoredRecord stored) {
        return stored.expiresAt() != null && stored.expiresAt().isBefore(Instant.now());
    }

    private boolean transition(
            ExecutionIdRecord record,
            Duration ttl,
            ExecutionIdRecord.Status... allowedStatuses) {
        final boolean[] updated = {false};
        records.computeIfPresent(record.getExecutionId(), (executionId, existing) -> {
            ExecutionIdRecord current = existing.record();
            boolean statusAllowed = java.util.Arrays.asList(allowedStatuses).contains(current.getStatus());
            if (!statusAllowed
                    || !java.util.Objects.equals(current.getOperation(), record.getOperation())
                    || !java.util.Objects.equals(current.getRequestHash(), record.getRequestHash())) {
                return existing;
            }
            updated[0] = true;
            return new StoredRecord(record, Instant.now().plus(effectiveTtl(ttl)));
        });
        return updated[0];
    }

    private record StoredRecord(ExecutionIdRecord record, Instant expiresAt) {
    }
}
