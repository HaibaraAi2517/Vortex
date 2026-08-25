package com.vortex.examples.realagent;

import java.util.List;
import java.util.Locale;

final class DemoConsole {

    private static final int WIDTH = 78;
    private static final String RULE = "=".repeat(WIDTH);
    private static final String THIN_RULE = "-".repeat(WIDTH);

    private DemoConsole() {
    }

    static void banner(String modelName, String vortexBaseUrl, String mode) {
        System.out.println();
        System.out.println(RULE);
        System.out.println(" VORTEX LIVE AGENT DEMO");
        System.out.println(" Real model + tool calls + durable memory + checkpoint recovery");
        System.out.println(THIN_RULE);
        System.out.println(" Model  : " + modelName);
        System.out.println(" Vortex : " + vortexBaseUrl);
        System.out.println(" Process: " + mode);
        System.out.println(RULE);
        System.out.flush();
    }

    static void section(int number, int total, String title) {
        System.out.println();
        System.out.println(RULE);
        System.out.println(" [" + number + "/" + total + "] " + title);
        System.out.println(RULE);
    }

    static void event(String actor, String message) {
        System.out.println("[" + actor + "] " + message);
    }

    static void block(String title, String content) {
        System.out.println();
        System.out.println("+ " + title);
        System.out.println(THIN_RULE);
        System.out.println(content == null || content.isBlank() ? "(empty)" : content.strip());
        System.out.println(THIN_RULE);
    }

    static void memory(List<VortexClient.RecallFragment> fragments) {
        System.out.println();
        System.out.println("+ VORTEX DURABLE MEMORY RECALL (" + fragments.size() + " fragment(s))");
        System.out.println(THIN_RULE);
        if (fragments.isEmpty()) {
            System.out.println("No matching durable memory was recalled for this turn.");
        }
        for (int index = 0; index < fragments.size(); index++) {
            VortexClient.RecallFragment fragment = fragments.get(index);
            System.out.println("[" + (index + 1) + "] id=" + fragment.fragmentId()
                    + " score=" + String.format(Locale.ROOT, "%.4f", fragment.score()));
            System.out.println("    " + fragment.content());
        }
        System.out.println(THIN_RULE);
    }

    static void task(VortexClient.TaskView task) {
        System.out.println();
        System.out.println("+ VORTEX TASK STATE");
        System.out.println(THIN_RULE);
        System.out.println("taskId            : " + task.taskId());
        System.out.println("status            : " + task.status());
        System.out.println("nodeCount         : " + task.nodeCount());
        System.out.println("latestCheckpoint  : " + task.latestCheckpointId());
        System.out.println(THIN_RULE);
    }

    static void checkpoint(String checkpointId, int nodeCount, String message) {
        System.out.println();
        System.out.println("+ CHECKPOINT PERSISTED");
        System.out.println(THIN_RULE);
        System.out.println("checkpointId : " + checkpointId);
        System.out.println("nodeCount    : " + nodeCount);
        System.out.println("proof        : " + message);
        System.out.println(THIN_RULE);
        System.out.flush();
    }

    static void interactiveHelp() {
        System.out.println();
        System.out.println("LIVE COMMANDS");
        System.out.println("  Ask any question       Talk to the recovered Agent");
        System.out.println("  /status                Read the live Vortex task state");
        System.out.println("  /memory                Inspect durable memory directly");
        System.out.println("  /help                  Show these commands");
        System.out.println("  /exit                  Complete the task and stop the demo");
        System.out.println();
        System.out.println("Try: What is the codename, and prove that this process recovered the old task?");
    }

    static void prompt() {
        System.out.println();
        System.out.print("YOU > ");
        System.out.flush();
    }

    static void success(String message) {
        System.out.println();
        System.out.println("[SUCCESS] " + message);
    }
}
