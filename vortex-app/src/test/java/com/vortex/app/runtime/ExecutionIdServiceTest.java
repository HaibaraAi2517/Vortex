package com.vortex.app.runtime;

import com.vortex.common.serialization.JsonMapperFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionIdServiceTest {

    private final ExecutionIdProperties properties = new ExecutionIdProperties();
    private final InMemoryExecutionIdStore store = new InMemoryExecutionIdStore();
    private final ExecutionIdService service = new ExecutionIdService(
            store,
            JsonMapperFactory.create(),
            properties);

    @Test
    void executeShouldReplayCompletedResponseForSameExecutionIdAndRequest() {
        properties.setTtl(Duration.ofMinutes(5));
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<Map<String, String>> first = service.execute(
                "exec-1",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(Map.of("taskId", "task-1"));
                });
        ResponseEntity<Map<String, String>> replay = service.execute(
                "exec-1",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(Map.of("taskId", "task-2"));
                });

        assertThat(calls).hasValue(1);
        assertThat(first.getBody()).containsEntry("taskId", "task-1");
        assertThat(replay.getHeaders().getFirst(ExecutionIdService.REPLAYED_HEADER_NAME)).isEqualTo("true");
        assertThat(replay.getBody()).containsEntry("taskId", "task-1");
    }

    @Test
    void executeShouldRejectSameExecutionIdWithDifferentRequest() {
        service.execute(
                "exec-2",
                "task.create",
                Map.of("description", "demo"),
                () -> ResponseEntity.ok(Map.of("taskId", "task-1")));

        assertThatThrownBy(() -> service.execute(
                "exec-2",
                "task.create",
                Map.of("description", "changed"),
                () -> ResponseEntity.ok(Map.of("taskId", "task-2"))))
                .isInstanceOf(ExecutionIdConflictException.class)
                .hasMessageContaining("different request");
    }

    @Test
    void executeShouldPreserveUncertainReservationWhenActionFails() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(
                "exec-3",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> service.execute(
                "exec-3",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(Map.of("taskId", "task-retry"));
                }))
                .isInstanceOf(ExecutionIdUncertainException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void completionWriteFailureShouldBlockSideEffectReplay() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionIdStore failingStore = new DelegatingStore(store) {
            @Override
            public boolean complete(ExecutionIdRecord record, Duration ttl) {
                throw new IllegalStateException("simulated Redis write failure");
            }
        };
        ExecutionIdService failingService = new ExecutionIdService(
                failingStore,
                JsonMapperFactory.create(),
                properties);

        assertThatThrownBy(() -> failingService.execute(
                "exec-complete-failure",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(Map.of("taskId", "task-1"));
                }))
                .isInstanceOf(ExecutionIdUncertainException.class);

        assertThatThrownBy(() -> service.execute(
                "exec-complete-failure",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(Map.of("taskId", "task-2"));
                }))
                .isInstanceOf(ExecutionIdUncertainException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void responseSerializationFailureShouldBlockSideEffectReplay() {
        AtomicInteger calls = new AtomicInteger();
        ObjectMapper failingMapper = JsonMapperFactory.create();
        ExecutionIdService failingService = new ExecutionIdService(store, failingMapper, properties);
        SelfReference response = new SelfReference();
        response.self = response;

        assertThatThrownBy(() -> failingService.execute(
                "exec-serialization-failure",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(response);
                }))
                .isInstanceOf(ExecutionIdUncertainException.class);

        assertThatThrownBy(() -> service.execute(
                "exec-serialization-failure",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(Map.of("taskId", "task-2"));
                }))
                .isInstanceOf(ExecutionIdUncertainException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void newServiceInstanceShouldReplayCompletedResponseFromPersistentStore() {
        service.execute(
                "exec-restart",
                "task.create",
                Map.of("description", "demo"),
                () -> ResponseEntity.ok(Map.of("taskId", "task-1")));
        ExecutionIdService restartedService = new ExecutionIdService(
                store,
                JsonMapperFactory.create(),
                properties);
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<Map<String, String>> replay = restartedService.execute(
                "exec-restart",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(Map.of("taskId", "task-2"));
                });

        assertThat(calls).hasValue(0);
        assertThat(replay.getBody()).containsEntry("taskId", "task-1");
    }

    @Test
    void inProgressReservationShouldNotExpireDuringLongAction() throws Exception {
        properties.setTtl(Duration.ofMillis(1));
        AtomicInteger calls = new AtomicInteger();

        service.execute(
                "exec-long-action",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return ResponseEntity.ok(Map.of("taskId", "task-1"));
                });

        assertThat(calls).hasValue(1);
    }

    private static final class SelfReference {
        public SelfReference self;
    }

    private static class DelegatingStore implements ExecutionIdStore {
        private final ExecutionIdStore delegate;

        private DelegatingStore(ExecutionIdStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<ExecutionIdRecord> get(String executionId) {
            return delegate.get(executionId);
        }

        @Override
        public boolean reserve(ExecutionIdRecord record, Duration ttl) {
            return delegate.reserve(record, ttl);
        }

        @Override
        public boolean complete(ExecutionIdRecord record, Duration ttl) {
            return delegate.complete(record, ttl);
        }

        @Override
        public boolean markUncertain(ExecutionIdRecord record, Duration ttl) {
            return delegate.markUncertain(record, ttl);
        }

        @Override
        public void remove(String executionId) {
            delegate.remove(executionId);
        }
    }
}
