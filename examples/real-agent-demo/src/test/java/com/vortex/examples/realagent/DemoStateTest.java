package com.vortex.examples.realagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DemoStateTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAndReadsCheckpointHandoffWithoutCredentials() throws Exception {
        Path statePath = tempDir.resolve("handoff.json");
        DemoState expected = new DemoState("run-1", "quickstart-test", "task-1", "checkpoint-1", "node-1");

        expected.writeAtomically(statePath);

        assertEquals(expected, DemoState.read(statePath));
    }
}
