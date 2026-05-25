package com.vortex.kernel.snapshot;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.ActionLogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Reads the Write-Ahead Log for task recovery and replay.
 *
 * Supports reading all entries, entries from a specific sequence number,
 * and looking up individual entries by UUID (for idempotency checks).
 */
@Slf4j
@Component
public class ActionLogReader {

    private final Path walDir;
    private final ObjectMapper objectMapper;

    public ActionLogReader(
            @Value("${vortex.kernel.snapshot.wal.dir:${java.io.tmpdir}/vortex-wal}") String walDirPath) {
        this.walDir = Paths.get(walDirPath);
        this.objectMapper = new ObjectMapper().findAndRegisterModules()
                .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
    }

    /**
     * Read all WAL entries for a task.
     */
    public List<ActionLogEntry> readAll(String taskId) {
        return readFrom(taskId, 0);
    }

    /**
     * Read WAL entries starting from a given sequence number (inclusive).
     *
     * @param taskId           the task to read
     * @param fromSequenceNumber read entries with seqNo >= this value
     * @return ordered list of entries
     */
    public List<ActionLogEntry> readFrom(String taskId, long fromSequenceNumber) {
        Path file = walDir.resolve(taskId + ".wal");
        if (!Files.exists(file)) {
            log.debug("WAL file not found for task={}", taskId);
            return Collections.emptyList();
        }

        List<ActionLogEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    ActionLogEntry entry = objectMapper.readValue(line, ActionLogEntry.class);
                    if (entry.getSequenceNumber() >= fromSequenceNumber) {
                        entries.add(entry);
                    }
                } catch (IOException e) {
                    // If the last line is corrupt (crash during write), skip it
                    if (reader.ready()) {
                        throw new CheckpointRecoveryException(
                                CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                                taskId,
                                null,
                                "Corrupt WAL entry encountered before EOF for taskId=" + taskId,
                                e);
                    } else {
                        log.debug("Trailing incomplete WAL line in task={} (expected after crash)", taskId);
                    }
                }
            }
        } catch (IOException e) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    taskId,
                    null,
                    "Failed to read WAL for taskId=" + taskId,
                    e);
        }

        log.debug("Read {} WAL entries for task={} from seqNo={}", entries.size(), taskId, fromSequenceNumber);
        return entries;
    }

    /**
     * Find a specific WAL entry by its UUID (for idempotent replay checks).
     */
    public Optional<ActionLogEntry> findEntry(String taskId, String entryId) {
        Path file = walDir.resolve(taskId + ".wal");
        if (!Files.exists(file)) return Optional.empty();

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                // Quick check: does the line contain the entryId string?
                if (line.contains(entryId)) {
                    try {
                        ActionLogEntry entry = objectMapper.readValue(line, ActionLogEntry.class);
                        if (entryId.equals(entry.getEntryId())) {
                            return Optional.of(entry);
                        }
                    } catch (IOException e) {
                        throw new CheckpointRecoveryException(
                                CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                                taskId,
                                null,
                                "Failed to parse matching WAL entry for taskId=" + taskId + " entryId=" + entryId,
                                e);
                    }
                }
            }
        } catch (IOException e) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED,
                    taskId,
                    null,
                    "Failed to search WAL for taskId=" + taskId,
                    e);
        }

        return Optional.empty();
    }

    /**
     * Check if a WAL file exists for the given task.
     */
    public boolean exists(String taskId) {
        return Files.exists(walDir.resolve(taskId + ".wal"));
    }

    /**
     * Delete the WAL file for a task.
     */
    public void delete(String taskId) {
        try {
            Files.deleteIfExists(walDir.resolve(taskId + ".wal"));
        } catch (IOException e) {
            log.warn("Failed to delete WAL file for task={}: {}", taskId, e.getMessage());
        }
    }
}
