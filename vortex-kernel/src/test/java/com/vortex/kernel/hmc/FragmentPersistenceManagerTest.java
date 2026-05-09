package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FragmentPersistenceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void failedPersistenceIsQueuedAndCanBeReplayed() {
        ToggleableL2WarmStore l2 = new ToggleableL2WarmStore();
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                5);
        FileBackedProcessedTaskStore processed = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-keys.txt"),
                128);
        FragmentPersistenceManager manager = new FragmentPersistenceManager(
                l2, l3, queue, processed, new MemorySloTracker(new SimpleMeterRegistry()), false);
        MemoryFragment fragment = MemoryFragment.builder()
                .id("persist-me")
                .namespace("ns")
                .content("payload")
                .embedding(new float[]{1.0f})
                .tokenCount(10)
                .build();

        l2.failWrites = true;
        manager.persistOrEnqueue(FragmentPersistenceTask.builder()
                .idempotencyKey("ns:persist-me:test")
                .reason("test")
                .fragment(fragment)
                .build());

        assertThat(queue.size()).isEqualTo(1);
        assertThat(l3.archivedIds).isEmpty();

        l2.failWrites = false;
        manager.replayPendingTasks();

        assertThat(queue.size()).isZero();
        assertThat(l2.upsertedIds).containsExactly("persist-me");
        assertThat(l3.archivedIds).containsExactly("persist-me");
        assertThat(processed.size()).isEqualTo(1);
    }

    @Test
    void processedIdempotencyKeyPreventsDuplicateReplaySideEffects() {
        ToggleableL2WarmStore l2 = new ToggleableL2WarmStore();
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq-duplicate.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                5);
        FileBackedProcessedTaskStore processed = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-duplicate.txt"),
                128);
        FragmentPersistenceManager manager = new FragmentPersistenceManager(
                l2, l3, queue, processed, new MemorySloTracker(new SimpleMeterRegistry()), false);
        MemoryFragment fragment = MemoryFragment.builder()
                .id("persist-once")
                .namespace("ns")
                .content("payload")
                .embedding(new float[]{1.0f})
                .tokenCount(10)
                .build();
        FragmentPersistenceTask task = FragmentPersistenceTask.builder()
                .idempotencyKey("ns:persist-once:test")
                .reason("test")
                .fragment(fragment)
                .build();

        manager.persistOrEnqueue(task);
        manager.persistOrEnqueue(task);

        assertThat(l2.upsertedIds).containsExactly("persist-once");
        assertThat(l3.archivedIds).containsExactly("persist-once");
        assertThat(processed.size()).isEqualTo(1);
    }

    @Test
    void replayResumesFromL3WhenL2AlreadySucceeded() {
        ToggleableL2WarmStore l2 = new ToggleableL2WarmStore();
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        l3.failArchives = true;
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq-partial.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                5);
        FileBackedProcessedTaskStore processed = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-partial.txt"),
                128);
        FragmentPersistenceManager manager = new FragmentPersistenceManager(
                l2, l3, queue, processed, new MemorySloTracker(new SimpleMeterRegistry()), false);

        MemoryFragment fragment = MemoryFragment.builder()
                .id("persist-l3-retry")
                .namespace("ns")
                .content("payload")
                .embedding(new float[]{1.0f})
                .tokenCount(10)
                .build();

        manager.persistOrEnqueue(FragmentPersistenceTask.builder()
                .idempotencyKey("ns:persist-l3-retry:test")
                .reason("test")
                .fragment(fragment)
                .build());

        assertThat(queue.size()).isEqualTo(1);
        assertThat(l2.upsertedIds).containsExactly("persist-l3-retry");
        assertThat(l3.archivedIds).isEmpty();

        l3.failArchives = false;
        manager.replayPendingTasks();

        assertThat(queue.size()).isZero();
        assertThat(l2.upsertedIds).containsExactly("persist-l3-retry");
        assertThat(l3.archivedIds).containsExactly("persist-l3-retry");
        assertThat(processed.size()).isEqualTo(1);
    }

    @Test
    void exhaustedDlqTaskIsDroppedAfterMaxAttempts() {
        ToggleableL2WarmStore l2 = new ToggleableL2WarmStore();
        l2.failWrites = true;
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq-max-attempts.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                2);
        FileBackedProcessedTaskStore processed = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-max-attempts.txt"),
                128);
        FragmentPersistenceManager manager = new FragmentPersistenceManager(
                l2, l3, queue, processed, new MemorySloTracker(new SimpleMeterRegistry()), false);

        MemoryFragment fragment = MemoryFragment.builder()
                .id("persist-never")
                .namespace("ns")
                .content("payload")
                .embedding(new float[]{1.0f})
                .tokenCount(10)
                .build();

        manager.persistOrEnqueue(FragmentPersistenceTask.builder()
                .idempotencyKey("ns:persist-never:test")
                .reason("test")
                .fragment(fragment)
                .build());
        assertThat(queue.size()).isEqualTo(1);

        manager.replayPendingTasks();

        assertThat(queue.size()).isZero();
        assertThat(l2.upsertedIds).isEmpty();
        assertThat(l3.archivedIds).isEmpty();
        assertThat(processed.size()).isZero();
    }

    @Test
    void enqueueDuringReplayIsNotBlockedUntilReplayCompletes() throws Exception {
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq-concurrent.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                5);

        queue.enqueue(FragmentPersistenceTask.builder()
                .idempotencyKey("ns:replaying:test")
                .reason("test")
                .fragment(MemoryFragment.builder()
                        .id("replaying")
                        .namespace("ns")
                        .content("payload")
                        .tokenCount(1)
                        .build())
                .build());

        CountDownLatch replayStarted = new CountDownLatch(1);
        CountDownLatch allowReplayToFinish = new CountDownLatch(1);
        AtomicReference<Throwable> replayFailure = new AtomicReference<>();

        Thread replayThread = new Thread(() -> {
            try {
                queue.replay(task -> {
                    replayStarted.countDown();
                    if (!allowReplayToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting for replay release");
                    }
                });
            } catch (Throwable t) {
                replayFailure.set(t);
            }
        });
        replayThread.start();

        assertThat(replayStarted.await(2, TimeUnit.SECONDS)).isTrue();

        long enqueueStartedAt = System.nanoTime();
        queue.enqueue(FragmentPersistenceTask.builder()
                .idempotencyKey("ns:new:test")
                .reason("test")
                .fragment(MemoryFragment.builder()
                        .id("new")
                        .namespace("ns")
                        .content("payload")
                        .tokenCount(1)
                        .build())
                .build());
        long enqueueElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueueStartedAt);

        allowReplayToFinish.countDown();
        replayThread.join(5000);

        assertThat(replayFailure.get()).isNull();
        assertThat(enqueueElapsedMs).isLessThan(500);
        assertThat(queue.size()).isEqualTo(1);
    }

    private static final class ToggleableL2WarmStore implements L2WarmStore {

        private boolean failWrites;
        private final List<String> upsertedIds = new ArrayList<>();

        @Override
        public void upsert(MemoryFragment fragment) {
            if (failWrites) {
                throw new IllegalStateException("simulated L2 failure");
            }
            upsertedIds.add(fragment.getId());
        }

        @Override
        public List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK) {
            return List.of();
        }

        @Override
        public Optional<MemoryFragment> get(String id) {
            return Optional.empty();
        }

        @Override
        public void delete(String id) {
        }
    }

    private static final class RecordingL3ColdStore implements L3ColdStore {

        private boolean failArchives;
        private final List<String> archivedIds = new ArrayList<>();

        @Override
        public void archiveFragment(MemoryFragment fragment) {
            if (failArchives) {
                throw new IllegalStateException("simulated L3 failure");
            }
            archivedIds.add(fragment.getId());
        }

        @Override
        public Optional<MemoryFragment> retrieveFragment(String id) {
            return Optional.empty();
        }

        @Override
        public String saveCheckpoint(TaskState state) {
            return "checkpoint";
        }

        @Override
        public Optional<TaskState> loadCheckpoint(String checkpointId) {
            return Optional.empty();
        }

        @Override
        public void deleteCheckpoint(String checkpointId) {
        }
    }
}
