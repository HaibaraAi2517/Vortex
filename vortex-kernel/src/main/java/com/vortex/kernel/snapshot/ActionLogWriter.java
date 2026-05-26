package com.vortex.kernel.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.ActionLogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Thread-safe Write-Ahead Log for task state mutations.
 *
 * JSONL format: one JSON-serialized {@link ActionLogEntry} per line.
 * One WAL file per task: {walDir}/{taskId}.wal
 *
 * Uses FileChannel.force(true) for durable fsync.
 * Monotonic sequence numbers per task.
 */
@Slf4j
@Component
public class ActionLogWriter {

    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final Path walDir;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileChannel> openChannels = new ConcurrentHashMap<>();

    public ActionLogWriter(
            @Value("${vortex.kernel.snapshot.wal.dir:${java.io.tmpdir}/vortex-wal}") String walDirPath) {
        this.walDir = Paths.get(walDirPath);
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        try {
            Files.createDirectories(this.walDir);
            log.info("WAL directory: {}", this.walDir.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create WAL directory: " + walDirPath, e);
        }
    }

    /**
     * Append an operation to the WAL and return the entry with assigned sequence number.
     */
    public ActionLogEntry append(String taskId, ActionLogEntry.OperationType operation, String payload) {
        return append(taskId, operation, payload, Instant.now());
    }

    /**
     * Append with explicit timestamp.
     */
    public ActionLogEntry append(String taskId, ActionLogEntry.OperationType operation, String payload, Instant timestamp) {
        walFileFor(walDir, taskId);
        AtomicLong counter = sequenceCounters.computeIfAbsent(taskId, k -> new AtomicLong(0));
        ReentrantLock lock = getTaskLock(taskId);
        lock.lock();
        try {
            long seqNo = counter.incrementAndGet();
            ActionLogEntry entry = ActionLogEntry.builder()
                    .sequenceNumber(seqNo)
                    .operation(operation)
                    .payload(payload)
                    .timestamp(timestamp)
                    .build();
            String jsonLine = objectMapper.writeValueAsString(entry) + "\n";
            byte[] bytes = jsonLine.getBytes(StandardCharsets.UTF_8);
            FileChannel channel = getOrCreateChannel(taskId);
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true); // durable fsync
            log.debug("WAL append taskId={} seqNo={} operation={}", taskId, seqNo, operation);
            return entry;
        } catch (IOException | UncheckedIOException e) {
            SnapshotHealthLogSupport.logRecoveryPrerequisiteFailure(log, "wal-write", taskId, null, e);
            throw new IllegalStateException("WAL write failed for task " + taskId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the current sequence number for a task.
     */
    public long currentSequenceNumber(String taskId) {
        AtomicLong counter = sequenceCounters.get(taskId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Ensure the next sequence number generated for taskId is strictly greater
     * than or equal to the provided value.
     */
    public void ensureSequenceAtLeast(String taskId, long sequenceNumberFloor) {
        if (sequenceNumberFloor <= 0) {
            return;
        }

        ReentrantLock lock = getTaskLock(taskId);
        lock.lock();
        try {
            sequenceCounters
                    .computeIfAbsent(taskId, ignored -> new AtomicLong(0))
                    .updateAndGet(current -> Math.max(current, sequenceNumberFloor));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Flush and fsync the WAL file for a task.
     */
    public void flush(String taskId) {
        ReentrantLock lock = taskLocks.get(taskId);
        if (lock != null) {
            lock.lock();
            try {
                FileChannel channel = openChannels.get(taskId);
                if (channel != null && channel.isOpen()) {
                    channel.force(true);
                }
            } catch (IOException e) {
                SnapshotHealthLogSupport.logRecoveryPrerequisiteFailure(log, "wal-flush", taskId, null, e);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Close the current channel without resetting task sequencing so the next append
     * reopens against the current WAL path after truncation or rotation.
     */
    public void rotate(String taskId) {
        ReentrantLock lock = taskLocks.get(taskId);
        if (lock == null) {
            return;
        }
        lock.lock();
        try {
            FileChannel channel = openChannels.remove(taskId);
            if (channel != null && channel.isOpen()) {
                try {
                    channel.force(true);
                } catch (IOException e) {
                    SnapshotHealthLogSupport.logRecoveryPrerequisiteFailure(log, "wal-rotate-force", taskId, null, e);
                } finally {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        SnapshotHealthLogSupport.logRecoveryPrerequisiteFailure(log, "wal-rotate-close", taskId, null, e);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Close the WAL file for a task and remove from caches.
     */
    public void close(String taskId) {
        ReentrantLock lock = taskLocks.get(taskId);
        if (lock == null) {
            sequenceCounters.remove(taskId);
            return;
        }

        boolean cleanupComplete = false;
        IOException closeFailure = null;
        lock.lock();
        try {
            FileChannel channel = openChannels.get(taskId);
            if (channel == null || !channel.isOpen()) {
                openChannels.remove(taskId);
                cleanupComplete = true;
            } else {
                try {
                    channel.force(true);
                } catch (IOException e) {
                    SnapshotHealthLogSupport.logRecoveryPrerequisiteFailure(log, "wal-close-force", taskId, null, e);
                }
                try {
                    channel.close();
                    openChannels.remove(taskId, channel);
                    cleanupComplete = true;
                } catch (IOException e) {
                    closeFailure = e;
                    SnapshotHealthLogSupport.logRecoveryPrerequisiteFailure(log, "wal-close-channel", taskId, null, e);
                }
            }
        } finally {
            lock.unlock();
        }

        if (!cleanupComplete) {
            throw new IllegalStateException("WAL close failed for task " + taskId, closeFailure);
        }

        taskLocks.remove(taskId, lock);
        sequenceCounters.remove(taskId);
    }

    /**
     * Get the WAL file path for a task.
     */
    public Path getWalFile(String taskId) {
        return walFileFor(walDir, taskId);
    }

    public <T> T withTaskLock(String taskId, Supplier<T> action) {
        ReentrantLock lock = getTaskLock(taskId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    // ---- Internal ----

    private ReentrantLock getTaskLock(String taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
    }

    static Path walFileFor(Path walDir, String taskId) {
        if (taskId == null || !SAFE_TASK_ID.matcher(taskId).matches()) {
            throw new IllegalArgumentException("Unsafe taskId: " + taskId);
        }
        Path normalizedWalDir = walDir.toAbsolutePath().normalize();
        Path resolved = normalizedWalDir.resolve(taskId + ".wal").normalize();
        if (!resolved.startsWith(normalizedWalDir)) {
            throw new IllegalArgumentException("Unsafe taskId path: " + taskId);
        }
        return resolved;
    }

    private FileChannel getOrCreateChannel(String taskId) throws IOException {
        FileChannel existing = openChannels.get(taskId);
        if (existing != null && existing.isOpen()) return existing;

        return openChannels.computeIfAbsent(taskId, k -> {
            try {
                Path file = getWalFile(taskId);
                return FileChannel.open(file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot open WAL file for task " + taskId, e);
            }
        });
    }
}
