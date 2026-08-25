package com.vortex.examples.realagent;

import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class AgentTools {

    private final Path repositoryRoot;
    private final VortexClient client;
    private final String recoveredTaskId;
    private final List<String> invocations = new ArrayList<>();

    AgentTools(Path repositoryRoot, VortexClient client, String recoveredTaskId) {
        this.repositoryRoot = repositoryRoot;
        this.client = client;
        this.recoveredTaskId = recoveredTaskId;
    }

    @Tool("Inspect the local Vortex Git repository and return its branch, commit, and dirty entry count.")
    public String inspectRepository() {
        String branch = runGit("branch", "--show-current");
        String commit = runGit("rev-parse", "--short", "HEAD");
        String status = runGit("status", "--porcelain");
        int dirtyEntries = status.isBlank() ? 0 : status.lines().toList().size();
        return record("inspectRepository", "branch=" + branch + ", commit=" + commit + ", dirtyEntries=" + dirtyEntries);
    }

    @Tool("Call the running Vortex health endpoint and return its current status.")
    public String inspectVortexHealth() {
        try {
            return record("inspectVortexHealth", "vortexHealth=" + client.health());
        } catch (IOException e) {
            throw new IllegalStateException("Vortex health tool failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vortex health tool was interrupted", e);
        }
    }

    @Tool("Read the recovered Vortex task and return its node count, status, and latest checkpoint ID.")
    public String inspectRecoveredTask() {
        if (recoveredTaskId == null || recoveredTaskId.isBlank()) {
            return record("inspectRecoveredTask", "No recovered task was supplied for this phase.");
        }
        try {
            VortexClient.TaskView task = client.getTask(recoveredTaskId);
            return record("inspectRecoveredTask", "taskId=" + task.taskId()
                    + ", status=" + task.status()
                    + ", nodeCount=" + task.nodeCount()
                    + ", latestCheckpointId=" + task.latestCheckpointId());
        } catch (IOException e) {
            throw new IllegalStateException("Recovered task tool failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Recovered task tool was interrupted", e);
        }
    }

    List<String> invocations() {
        return List.copyOf(invocations);
    }

    private String runGit(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repositoryRoot.toString());
        command.addAll(List.of(arguments));
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "timeout";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 ? output : "unavailable(" + output + ")";
        } catch (IOException e) {
            return "unavailable(" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String record(String toolName, String result) {
        invocations.add(toolName);
        System.out.println("TOOL CALL: " + toolName);
        System.out.println("TOOL RESULT: " + result);
        return result;
    }
}
