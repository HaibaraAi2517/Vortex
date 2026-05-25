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
        AtomicLong counter = sequenceCounters.computeIfAbsent(taskId, k -> new AtomicLong(0));
        long seqNo = counter.incrementAndGet();

        ActionLogEntry entry = ActionLogEntry.builder()
                .sequenceNumber(seqNo)
                .operation(operation)
                .payload(payload)
                .timestamp(timestamp)
                .build();

        ReentrantLock lock = getTaskLock(taskId);
        lock.lock();
        try {
            String jsonLine = objectMapper.writeValueAsString(entry) + "\n";
            byte[] bytes = jsonLine.getBytes(StandardCharsets.UTF_8);
            FileChannel channel = getOrCreateChannel(taskId);
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true); // durable fsync
        } catch (IOException e) {
            counter.decrementAndGet();
            log.error("WAL write failed for task={} seqNo={}: {}", taskId, seqNo, e.getMessage());
            throw new IllegalStateException("WAL write failed for task " + taskId, e);
        } finally {
            lock.unlock();
        }

        log.debug("WAL append taskId={} seqNo={} operation={}", taskId, seqNo, operation);
        return entry;
    }

    /**
     * Get the current sequence number for a task.
     */
    public long currentSequenceNumber(String taskId) {
        AtomicLong counter = sequenceCounters.get(taskId);
        return counter != null ? counter.get() : 0;
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
                log.error("WAL flush failed for task={}: {}", taskId, e.getMessage());
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
                    log.warn("WAL force failed during rotate for task={}: {}", taskId, e.getMessage());
                } finally {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        log.warn("WAL channel close failed during rotate for task={}: {}", taskId, e.getMessage());
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
        if (lock != null) {
            lock.lock();
            try {
                FileChannel channel = openChannels.remove(taskId);
                if (channel != null && channel.isOpen()) {
                    try {
                        channel.force(true);
                    } catch (IOException e) {
                        log.warn("WAL force failed for task={}, proceeding to close: {}", taskId, e.getMessage());
                    } finally {
                        try {
                            channel.close();
                        } catch (IOException e) {
                            log.error("WAL channel close failed for task={}: {}", taskId, e.getMessage());
                        }
                    }
                }
            } finally {
                lock.unlock();
                taskLocks.remove(taskId);
            }
        }
        sequenceCounters.remove(taskId);
    }

    /**
     * Get the WAL file path for a task.
     */
    public Path getWalFile(String taskId) {
        return walDir.resolve(taskId + ".wal");
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
