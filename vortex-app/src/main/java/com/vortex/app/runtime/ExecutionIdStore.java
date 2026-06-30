package com.vortex.app.runtime;

import java.time.Duration;
import java.util.Optional;

public interface ExecutionIdStore {

    Optional<ExecutionIdRecord> get(String executionId);

    boolean reserve(ExecutionIdRecord record, Duration ttl);

    void complete(ExecutionIdRecord record, Duration ttl);

    void remove(String executionId);
}
