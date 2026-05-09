package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FileBackedProcessedTaskStore {

    private final Path storePath;
    private final int maxEntries;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();
    private Map<String, ProcessedKeyRecord> processedKeys;

    public FileBackedProcessedTaskStore(
            @Value("${vortex.kernel.persistence.processed-keys.path:${java.io.tmpdir}/vortex-hmc-processed-keys.txt}") String storePath,
            @Value("${vortex.kernel.persistence.processed-keys.max-entries:10000}") int maxEntries) {
        this(Path.of(storePath), maxEntries, new ObjectMapper().findAndRegisterModules());
    }

    FileBackedProcessedTaskStore(Path storePath) {
        this(storePath, 10_000, new ObjectMapper().findAndRegisterModules());
    }

    FileBackedProcessedTaskStore(Path storePath, int maxEntries) {
        this(storePath, maxEntries, new ObjectMapper().findAndRegisterModules());
    }

    FileBackedProcessedTaskStore(Path storePath, int maxEntries, ObjectMapper objectMapper) {
        this.storePath = storePath;
        this.maxEntries = Math.max(1, maxEntries);
        this.objectMapper = objectMapper;
    }

    public boolean contains(String idempotencyKey) {
        synchronized (lock) {
            loadIfNeeded();
            return processedKeys.containsKey(idempotencyKey);
        }
    }

    public void markProcessed(String idempotencyKey) {
        synchronized (lock) {
            loadIfNeeded();
            if (processedKeys.containsKey(idempotencyKey)) {
                return;
            }
            ProcessedKeyRecord record = new ProcessedKeyRecord(idempotencyKey, Instant.now().toEpochMilli());
            processedKeys.put(idempotencyKey, record);
            if (processedKeys.size() > maxEntries) {
                trimToMaxEntries();
                rewriteAll();
                return;
            }
            appendRecord(record);
        }
    }

    public int size() {
        synchronized (lock) {
            loadIfNeeded();
            return processedKeys.size();
        }
    }

    private void loadIfNeeded() {
        if (processedKeys != null) {
            return;
        }
        processedKeys = readAll();
    }

    private Map<String, ProcessedKeyRecord> readAll() {
        ensureStoreFile();
        try {
            Map<String, ProcessedKeyRecord> loaded = new LinkedHashMap<>();
            for (String line : Files.readAllLines(storePath, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                ProcessedKeyRecord record = parseRecord(line);
                loaded.put(record.idempotencyKey(), record);
            }
            if (loaded.size() > maxEntries) {
                loaded = newestEntries(loaded, maxEntries);
                processedKeys = loaded;
                rewriteAll();
            }
            return loaded;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read processed key store " + storePath, e);
        }
    }

    private ProcessedKeyRecord parseRecord(String line) throws IOException {
        String trimmed = line.trim();
        if (trimmed.startsWith("{")) {
            return objectMapper.readValue(trimmed, ProcessedKeyRecord.class);
        }
        return new ProcessedKeyRecord(trimmed, 0L);
    }

    private void appendRecord(ProcessedKeyRecord record) {
        ensureStoreFile();
        try {
            Files.writeString(
                    storePath,
                    objectMapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to mark processed key " + record.idempotencyKey(), e);
        }
    }

    private void trimToMaxEntries() {
        processedKeys = newestEntries(processedKeys, maxEntries);
    }

    private Map<String, ProcessedKeyRecord> newestEntries(Map<String, ProcessedKeyRecord> source, int limit) {
        int skip = Math.max(0, source.size() - limit);
        Map<String, ProcessedKeyRecord> trimmed = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, ProcessedKeyRecord> entry : source.entrySet()) {
            if (index++ < skip) {
                continue;
            }
            trimmed.put(entry.getKey(), entry.getValue());
        }
        return trimmed;
    }

    private void rewriteAll() {
        ensureStoreFile();
        try {
            StringBuilder builder = new StringBuilder();
            for (ProcessedKeyRecord record : processedKeys.values()) {
                builder.append(objectMapper.writeValueAsString(record)).append(System.lineSeparator());
            }
            Files.writeString(
                    storePath,
                    builder.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rewrite processed key store " + storePath, e);
        }
    }

    private void ensureStoreFile() {
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(storePath)) {
                Files.createFile(storePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize processed key store " + storePath, e);
        }
    }

    private record ProcessedKeyRecord(String idempotencyKey, long processedAtEpochMillis) {
    }
}
