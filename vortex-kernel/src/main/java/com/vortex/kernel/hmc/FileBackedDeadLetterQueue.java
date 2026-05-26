package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.health.MemoryHealthCodes;
import com.vortex.kernel.health.MemoryDurabilityLogSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FileBackedDeadLetterQueue {

    private final Path queuePath;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;
    private final ReentrantReadWriteLock queueLock = new ReentrantReadWriteLock();

    @Autowired
    public FileBackedDeadLetterQueue(
            @Value("${vortex.kernel.persistence.dlq.path:${java.io.tmpdir}/vortex-hmc-dlq.jsonl}") String queuePath,
            @Value("${vortex.kernel.persistence.dlq.max-attempts:5}") int maxAttempts) {
        this(Path.of(queuePath), new ObjectMapper().findAndRegisterModules(), maxAttempts);
    }

    FileBackedDeadLetterQueue(Path queuePath, ObjectMapper objectMapper) {
        this(queuePath, objectMapper, 5);
    }

    FileBackedDeadLetterQueue(Path queuePath, ObjectMapper objectMapper, int maxAttempts) {
        this.queuePath = queuePath;
        this.objectMapper = objectMapper;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public void enqueue(FragmentPersistenceTask task) {
        queueLock.writeLock().lock();
        try {
            appendTask(task);
        } finally {
            queueLock.writeLock().unlock();
        }
    }

    public boolean enqueueForRetry(FragmentPersistenceTask task, Exception failure) {
        queueLock.writeLock().lock();
        try {
            task.setAttemptCount(task.getAttemptCount() + 1);
            task.setLastFailure(failure == null ? null : failure.getMessage());
            if (task.getAttemptCount() >= maxAttempts) {
                return false;
            }
            appendTask(task);
            return true;
        } finally {
            queueLock.writeLock().unlock();
        }
    }

    public ReplayReport replay(PersistenceTaskConsumer consumer) {
        List<FragmentPersistenceTask> snapshot = snapshotTasks();
        if (snapshot.isEmpty()) {
            return new ReplayReport(0, 0, 0);
        }

        List<FragmentPersistenceTask> failedTasks = new ArrayList<>();
        Set<String> replayedTaskKeys = snapshot.stream()
                .map(FragmentPersistenceTask::getIdempotencyKey)
                .collect(Collectors.toSet());
        int recovered = 0;
        int discarded = 0;
        for (FragmentPersistenceTask task : snapshot) {
            try {
                consumer.accept(task);
                recovered++;
            } catch (Exception ex) {
                task.setAttemptCount(task.getAttemptCount() + 1);
                task.setLastFailure(ex.getMessage());
                if (task.getAttemptCount() >= maxAttempts) {
                    discarded++;
                    MemoryDurabilityLogSupport.logCritical(
                            log,
                            MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                            MemoryDurabilityLogSupport.CHAIN_MEMORY_PERSISTENCE,
                            MemoryDurabilityLogSupport.PHASE_DLQ_DROP,
                            null,
                            null,
                            task.getFragment() == null ? null : task.getFragment().getId(),
                            task.getIdempotencyKey(),
                            null,
                            "DLQ_MAX_ATTEMPTS_EXHAUSTED",
                            "DLQ replay discarded a persistence task after exhausting the retry budget.",
                            ex,
                            Map.of(
                                    "attempts", task.getAttemptCount(),
                                    "reason", task.getReason(),
                                    "lastFailure", safe(task.getLastFailure()),
                                    "failedPhase", failedPhase(task)));
                } else {
                    failedTasks.add(task);
                }
            }
        }
        mergeReplayOutcome(replayedTaskKeys, failedTasks);
        return new ReplayReport(recovered, failedTasks.size(), discarded);
    }

    public int size() {
        queueLock.readLock().lock();
        try {
            return readAll().size();
        } finally {
            queueLock.readLock().unlock();
        }
    }

    private List<FragmentPersistenceTask> snapshotTasks() {
        queueLock.readLock().lock();
        try {
            return readAll();
        } finally {
            queueLock.readLock().unlock();
        }
    }

    private void mergeReplayOutcome(Set<String> replayedTaskKeys, List<FragmentPersistenceTask> failedTasks) {
        queueLock.writeLock().lock();
        try {
            Map<String, FragmentPersistenceTask> merged = new LinkedHashMap<>();
            for (FragmentPersistenceTask task : readAll()) {
                if (!replayedTaskKeys.contains(task.getIdempotencyKey())) {
                    merged.put(task.getIdempotencyKey(), task);
                }
            }
            for (FragmentPersistenceTask task : failedTasks) {
                merged.put(task.getIdempotencyKey(), task);
            }
            rewriteAll(new ArrayList<>(merged.values()));
        } finally {
            queueLock.writeLock().unlock();
        }
    }

    private void appendTask(FragmentPersistenceTask task) {
        ensureQueueFile();
        try {
            String line = objectMapper.writeValueAsString(task) + System.lineSeparator();
            Files.writeString(queuePath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append DLQ task " + task.getIdempotencyKey(), e);
        }
    }

    private List<FragmentPersistenceTask> readAll() {
        ensureQueueFile();
        try {
            List<FragmentPersistenceTask> tasks = new ArrayList<>();
            for (String line : Files.readAllLines(queuePath, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                tasks.add(objectMapper.readValue(line, FragmentPersistenceTask.class));
            }
            return tasks;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read DLQ file " + queuePath, e);
        }
    }

    private void rewriteAll(List<FragmentPersistenceTask> tasks) {
        ensureQueueFile();
        try {
            List<String> lines = new ArrayList<>(tasks.size());
            for (FragmentPersistenceTask task : tasks) {
                lines.add(objectMapper.writeValueAsString(task));
            }
            Files.write(queuePath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rewrite DLQ file " + queuePath, e);
        }
    }

    private void ensureQueueFile() {
        try {
            Path parent = queuePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(queuePath)) {
                Files.createFile(queuePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize DLQ file " + queuePath, e);
        }
    }

    @FunctionalInterface
    public interface PersistenceTaskConsumer {
        void accept(FragmentPersistenceTask task) throws Exception;
    }

    public record ReplayReport(int recoveredCount, int retriedCount, int discardedCount) {
    }

    private static String failedPhase(FragmentPersistenceTask task) {
        if (task == null) {
            return MemoryDurabilityLogSupport.PHASE_L3_ARCHIVE;
        }
        if (!task.isL2Persisted() && task.getFragment() != null && task.getFragment().getEmbedding() != null) {
            return MemoryDurabilityLogSupport.PHASE_L2_UPSERT;
        }
        return MemoryDurabilityLogSupport.PHASE_L3_ARCHIVE;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }
}
