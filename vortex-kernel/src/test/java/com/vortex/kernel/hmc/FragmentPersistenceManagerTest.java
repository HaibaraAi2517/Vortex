package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class FragmentPersistenceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void failedPersistenceIsQueuedAndCanBeReplayed(CapturedOutput output) {
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
        assertThat(output.toString()).contains("memory_durability_degraded");
        assertThat(output.toString()).contains("healthCode=memory_persistence_success_rate_low");
        assertThat(output.toString()).contains("chain=memory-persistence");
        assertThat(output.toString()).contains("phase=l2-upsert");
        assertThat(output.toString()).contains("severity=warning");

        l2.failWrites = false;
        manager.replayPendingTasks();

        assertThat(queue.size()).isZero();
        assertThat(l2.upsertedIds).containsExactly("persist-me");
        assertThat(l3.archivedIds).containsExactly("persist-me");
        assertThat(processed.size()).isEqualTo(1);
        assertThat(output.toString()).contains("memory_durability_recovered");
        assertThat(output.toString()).contains("phase=complete");
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
    void rejectedAsyncTaskIsDeferredToDlqInsteadOfRunningOnCaller() {
        ToggleableL2WarmStore l2 = new ToggleableL2WarmStore();
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq-rejected.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                5);
        FileBackedProcessedTaskStore processed = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-rejected.txt"),
                128);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("saturated");
        };
        FragmentPersistenceManager manager = new FragmentPersistenceManager(
                l2, l3, queue, processed, new MemorySloTracker(new SimpleMeterRegistry()), false,
                rejectingExecutor);
        MemoryFragment fragment = MemoryFragment.builder()
                .id("deferred")
                .namespace("ns")
                .content("payload")
                .embedding(new float[]{1.0f})
                .tokenCount(10)
                .build();

        manager.persistAsync(fragment, "executor-saturated");

        assertThat(queue.size()).isEqualTo(1);
        assertThat(l2.upsertedIds).isEmpty();
        assertThat(l3.archivedIds).isEmpty();

        manager.replayPendingTasks();
        assertThat(queue.size()).isZero();
        assertThat(l2.upsertedIds).containsExactly("deferred");
        assertThat(l3.archivedIds).containsExactly("deferred");
    }

    @Test
    void asyncBatchUsesBoundedExecutorSubmissionsAndPersistsEveryFragment() {
        ToggleableL2WarmStore l2 = new ToggleableL2WarmStore();
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq-batch.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                5);
        FileBackedProcessedTaskStore processed = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-batch.txt"),
                128);
        AtomicInteger submissions = new AtomicInteger();
        Executor directExecutor = command -> {
            submissions.incrementAndGet();
            command.run();
        };
        FragmentPersistenceManager manager = new FragmentPersistenceManager(
                l2, l3, queue, processed, new MemorySloTracker(new SimpleMeterRegistry()), false,
                directExecutor);
        List<MemoryFragment> fragments = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> MemoryFragment.builder()
                        .id("batch-" + index)
                        .namespace("ns")
                        .content("payload")
                        .embedding(new float[]{1.0f})
                        .tokenCount(1)
                        .build())
                .toList();

        manager.persistAsyncBatch(fragments, "batch-test");

        assertThat(submissions).hasValue(3);
        assertThat(l2.upsertedIds).hasSize(40);
        assertThat(l3.archivedIds).hasSize(40);
        assertThat(processed.size()).isEqualTo(40);
        assertThat(queue.size()).isZero();
    }

    @Test
    void rejectedAsyncBatchDefersEveryTaskToDlq() {
        ToggleableL2WarmStore l2 = new ToggleableL2WarmStore();
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq-rejected-batch.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                5);
        FileBackedProcessedTaskStore processed = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-rejected-batch.txt"),
                128);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("saturated");
        };
        FragmentPersistenceManager manager = new FragmentPersistenceManager(
                l2, l3, queue, processed, new MemorySloTracker(new SimpleMeterRegistry()), false,
                rejectingExecutor);
        List<MemoryFragment> fragments = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> MemoryFragment.builder()
                        .id("deferred-batch-" + index)
                        .namespace("ns")
                        .content("payload")
                        .embedding(new float[]{1.0f})
                        .tokenCount(1)
                        .build())
                .toList();

        manager.persistAsyncBatch(fragments, "executor-saturated");

        assertThat(queue.size()).isEqualTo(3);
        assertThat(l2.upsertedIds).isEmpty();
        assertThat(l3.archivedIds).isEmpty();
    }

    @Test
    void awaitQuiescenceWaitsForSubmittedBatchWork() {
        ToggleableL2WarmStore l2 = new ToggleableL2WarmStore();
        RecordingL3ColdStore l3 = new RecordingL3ColdStore();
        FileBackedDeadLetterQueue queue = new FileBackedDeadLetterQueue(
                tempDir.resolve("hmc-dlq-drain.jsonl"),
                new ObjectMapper().findAndRegisterModules(),
                5);
        FileBackedProcessedTaskStore processed = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-drain.txt"),
                128);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            FragmentPersistenceManager manager = new FragmentPersistenceManager(
                    l2, l3, queue, processed, new MemorySloTracker(new SimpleMeterRegistry()), false,
                    executor);
            List<MemoryFragment> fragments = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> MemoryFragment.builder()
                            .id("drain-" + index)
                            .namespace("ns")
                            .content("payload")
                            .embedding(new float[]{1.0f})
                            .tokenCount(1)
                            .build())
                    .toList();

            manager.persistAsyncBatch(fragments, "drain-test");

            assertThat(manager.awaitQuiescence(java.time.Duration.ofSeconds(5))).isTrue();
            assertThat(l2.upsertedIds).hasSize(20);
            assertThat(l3.archivedIds).hasSize(20);
        } finally {
            executor.shutdownNow();
        }
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
    void exhaustedDlqTaskIsDroppedAfterMaxAttempts(CapturedOutput output) {
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
        assertThat(output.toString()).contains("memory_durability_degraded");
        assertThat(output.toString()).contains("healthCode=memory_persistence_success_rate_low");
        assertThat(output.toString()).contains("phase=dlq-drop");
        assertThat(output.toString()).contains("severity=critical");
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
