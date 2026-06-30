package com.vortex.app.runtime;

import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
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
    void executeShouldReleaseReservationWhenActionFails() {
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

        ResponseEntity<Map<String, String>> retry = service.execute(
                "exec-3",
                "task.create",
                Map.of("description", "demo"),
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.ok(Map.of("taskId", "task-retry"));
                });

        assertThat(calls).hasValue(2);
        assertThat(retry.getBody()).containsEntry("taskId", "task-retry");
    }
}
