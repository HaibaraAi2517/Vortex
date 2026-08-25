package com.vortex.examples.realagent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoConsoleTest {

    @Test
    void rendersVisibleRecoveryAndInteractionEvidence() {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            DemoConsole.banner("deepseek-chat", "http://127.0.0.1:8080", "PHASE2");
            DemoConsole.section(5, 5, "Interact with the recovered Agent");
            DemoConsole.task(new VortexClient.TaskView(
                    "task-1", "RUNNING", "quickstart-test", 2, "checkpoint-2", Map.of()));
            DemoConsole.checkpoint("checkpoint-3", 3, "Interactive turn 1 is durable and recoverable.");
            DemoConsole.interactiveHelp();
            DemoConsole.prompt();
        } finally {
            System.setOut(original);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertAll(
                () -> assertTrue(rendered.contains("VORTEX LIVE AGENT DEMO")),
                () -> assertTrue(rendered.contains("[5/5] Interact with the recovered Agent")),
                () -> assertTrue(rendered.contains("VORTEX TASK STATE")),
                () -> assertTrue(rendered.contains("CHECKPOINT PERSISTED")),
                () -> assertTrue(rendered.contains("checkpoint-3")),
                () -> assertTrue(rendered.contains("/status")),
                () -> assertTrue(rendered.contains("YOU >")));
    }
}
