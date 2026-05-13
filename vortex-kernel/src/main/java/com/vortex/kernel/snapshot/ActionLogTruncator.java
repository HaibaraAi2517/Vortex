package com.vortex.kernel.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.ActionLogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Truncates the Write-Ahead Log after a successful checkpoint.
 *
 * After a checkpoint at sequence N, all WAL entries up to N are
 * guaranteed to be persisted in L3 and can be safely removed.
 * This prevents unbounded WAL file growth.
 *
 * Strategy: read-write-rename (safe against crash mid-truncation).
 */
@Slf4j
@Component
public class ActionLogTruncator {

    private final ActionLogReader reader;
    private final Path walDir;
    private final ObjectMapper objectMapper;

    public ActionLogTruncator(
            ActionLogReader reader,
            @Value("${vortex.kernel.snapshot.wal.dir:${java.io.tmpdir}/vortex-wal}") String walDirPath) {
        this.reader = reader;
        this.walDir = Paths.get(walDirPath);
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Truncate WAL entries with sequence number <= truncationPoint.
     *
     * Only entries with seqNo > truncationPoint are retained.
     * Uses an atomic file rename to prevent corruption if the JVM crashes during truncation.
     *
     * @param taskId          the task whose WAL to truncate
     * @param truncationPoint all entries with seqNo <= this will be removed
     */
    public void truncate(String taskId, long truncationPoint) {
        Path walFile = walDir.resolve(taskId + ".wal");
        if (!Files.exists(walFile)) {
            log.debug("No WAL file to truncate for task={}", taskId);
            return;
        }

        try {
            // Read all entries
            List<ActionLogEntry> entries = reader.readAll(taskId);

            // Filter to keep only entries after truncation point
            List<ActionLogEntry> retained = entries.stream()
                    .filter(e -> e.getSequenceNumber() > truncationPoint)
                    .toList();

            if (retained.size() == entries.size()) {
                log.debug("WAL truncation for task={}: no entries to remove (truncPoint={}, total={})",
                        taskId, truncationPoint, entries.size());
                return;
            }

            // Write retained entries to a temp file, then rename atomically
            Path tempFile = walDir.resolve(taskId + ".wal.tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
                for (ActionLogEntry entry : retained) {
                    writer.write(objectMapper.writeValueAsString(entry) + "\n");
                }
                writer.flush();
            }

            // Atomic rename (REPLACE_EXISTING without ATOMIC_MOVE on Windows)
            Files.move(tempFile, walFile, StandardCopyOption.REPLACE_EXISTING);

            log.info("WAL truncated for task={}: removed {} entries, retained {} entries (truncPoint={})",
                    taskId, entries.size() - retained.size(), retained.size(), truncationPoint);

        } catch (IOException e) {
            log.error("WAL truncation failed for task={}: {}", taskId, e.getMessage());
            // Clean up temp file if it exists
            try {
                Files.deleteIfExists(walDir.resolve(taskId + ".wal.tmp"));
            } catch (IOException ignored) {}
        }
    }
}
