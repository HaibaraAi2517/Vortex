package com.vortex.examples.realagent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

record DemoState(
        String runId,
        String namespace,
        String taskId,
        String checkpointId,
        String firstNodeId) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static DemoState read(Path path) throws IOException {
        return OBJECT_MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), DemoState.class);
    }

    void writeAtomically(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path pending = Path.of(path + ".pending");
        Files.writeString(pending, OBJECT_MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
        try {
            Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
