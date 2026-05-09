package com.vortex.kernel.hmc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileBackedProcessedTaskStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void retainsOnlyNewestConfiguredEntries() {
        FileBackedProcessedTaskStore store = new FileBackedProcessedTaskStore(
                tempDir.resolve("processed-keys.jsonl"),
                2);

        store.markProcessed("k1");
        store.markProcessed("k2");
        store.markProcessed("k3");

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.contains("k1")).isFalse();
        assertThat(store.contains("k2")).isTrue();
        assertThat(store.contains("k3")).isTrue();
    }

    @Test
    void rewritesStoreFileWhenEntryCountExceedsCapacity() throws Exception {
        Path storeFile = tempDir.resolve("processed-keys.jsonl");
        FileBackedProcessedTaskStore store = new FileBackedProcessedTaskStore(storeFile, 2);

        store.markProcessed("k1");
        store.markProcessed("k2");
        store.markProcessed("k3");

        List<String> lines = Files.readAllLines(storeFile, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).contains("k2");
        assertThat(lines.getLast()).contains("k3");
    }
}
