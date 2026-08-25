package com.vortex.examples.realagent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RealAgentDemo {

    private static final String PRIVATE_FACTS = "Private release facts for this demo: deployment codename is Aurora Ledger; "
            + "freeze time is 2026-09-18 16:00 Asia/Shanghai; approval owner is Lin-7. "
            + "After a crash, continue from the durable checkpoint and do not repeat completed phase-one work.";

    private RealAgentDemo() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        VortexClient client = new VortexClient(config.vortexBaseUrl(), config.vortexToken());
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(config.modelBaseUrl())
                .apiKey(config.modelApiKey())
                .modelName(config.modelName())
                .temperature(0.0)
                .maxTokens(800)
                .timeout(Duration.ofSeconds(config.modelTimeoutSeconds()))
                .maxRetries(1)
                .build();

        System.out.println("Vortex base URL: " + config.vortexBaseUrl());
        System.out.println("Model base URL: " + config.modelBaseUrl());
        System.out.println("Model name: " + config.modelName());
        System.out.println("Demo mode: " + config.mode());
        System.out.println();

        if (config.mode() == Mode.PHASE1) {
            runPhaseOne(config, client, chatModel);
        } else {
            runPhaseTwo(config, client, chatModel);
        }
    }

    private static void runPhaseOne(Config config, VortexClient client, ChatModel chatModel) throws Exception {
        String runId = config.runId();
        String namespace = config.namespace();

        System.out.println("=== PHASE 1: real model, Vortex memory, and tools ===");
        System.out.println("Namespace: " + namespace);
        int stored = client.store(PRIVATE_FACTS, namespace, List.of("real-agent-demo", "private-release-facts"));
        System.out.println("Stored Vortex fragments: " + stored);

        Assistant baseline = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .systemMessage("Respond in Simplified Chinese. Answer only from information available in this new "
                        + "conversation. If facts are missing, say so.")
                .build();
        String baselineAnswer = baseline.chat(
                "What are the private deployment codename, freeze time, and approval owner for this demo?");
        System.out.println();
        System.out.println("WITHOUT VORTEX MEMORY:");
        System.out.println(baselineAnswer);

        VortexClient.TaskView task = client.createTask(
                "Real model and tool agent crash recovery demo", namespace, executionId(runId, "task-create"));
        AgentTools tools = new AgentTools(config.repositoryRoot(), client, null);
        VortexMemoryTransformer transformer = new VortexMemoryTransformer(client, namespace, 5, 768);
        Assistant agent = agent(chatModel, transformer, tools);

        String answer = agent.chat("Before answering, you MUST call inspectRepository and inspectVortexHealth. "
                + "Then produce a concise phase-one release report containing the private deployment codename, "
                + "freeze time, approval owner, repository evidence, and Vortex health. "
                + "End with exactly: PHASE_ONE_COMPLETE");

        requireTools(tools, "inspectRepository", "inspectVortexHealth");
        requireMemory(transformer);
        System.out.println();
        printRecalledMemory(transformer);
        System.out.println("REAL AGENT ANSWER:");
        System.out.println(answer);

        VortexClient.NodeView node = client.appendNode(task.taskId(), "ACTION", answer,
                executionId(runId, "phase-one-node"));
        client.completeNode(task.taskId(), node.nodeId(), "PHASE_ONE_COMPLETE",
                executionId(runId, "phase-one-node-complete"));
        String checkpointId = client.checkpoint(task.taskId(), executionId(runId, "phase-one-checkpoint"));
        new DemoState(runId, namespace, task.taskId(), checkpointId, node.nodeId())
                .writeAtomically(config.stateFile());

        System.out.println();
        System.out.println("CHECKPOINT READY: taskId=" + task.taskId()
                + ", checkpointId=" + checkpointId + ", nodeCount=1");
        System.out.println("Phase-one process is now waiting to be terminated by the orchestrator.");
        System.out.flush();
        while (true) {
            Thread.sleep(5_000);
        }
    }

    private static void runPhaseTwo(Config config, VortexClient client, ChatModel chatModel) throws Exception {
        DemoState state = DemoState.read(config.stateFile());
        System.out.println("=== PHASE 2: recover checkpoint and continue ===");
        VortexClient.TaskView recovered = client.recover(
                state.taskId(), state.checkpointId(), executionId(state.runId(), "recover"));
        if (recovered.nodeCount() < 1) {
            throw new IllegalStateException("Recovered task does not contain the phase-one node.");
        }
        System.out.println("RECOVERED: taskId=" + recovered.taskId()
                + ", checkpointId=" + state.checkpointId()
                + ", nodeCount=" + recovered.nodeCount());

        AgentTools tools = new AgentTools(config.repositoryRoot(), client, recovered.taskId());
        VortexMemoryTransformer transformer = new VortexMemoryTransformer(client, state.namespace(), 5, 768);
        Assistant agent = agent(chatModel, transformer, tools);
        String answer = agent.chat("This process started after the phase-one process was killed. "
                + "Before answering, you MUST call inspectRecoveredTask and inspectRepository. "
                + "Use Vortex durable memory and recovered task evidence to produce a concise continuation report. "
                + "State that phase one was not repeated, include the private deployment codename and approval owner, "
                + "and end with exactly: RECOVERY_COMPLETE");

        requireTools(tools, "inspectRecoveredTask", "inspectRepository");
        requireMemory(transformer);
        System.out.println();
        printRecalledMemory(transformer);
        System.out.println("RECOVERED AGENT ANSWER:");
        System.out.println(answer);

        VortexClient.NodeView finalNode = client.appendNode(recovered.taskId(), "ACTION", answer,
                executionId(state.runId(), "phase-two-node"));
        client.completeNode(recovered.taskId(), finalNode.nodeId(), "RECOVERY_COMPLETE",
                executionId(state.runId(), "phase-two-node-complete"));
        String resumedCheckpoint = client.checkpoint(
                recovered.taskId(), executionId(state.runId(), "phase-two-checkpoint"));
        client.completeTask(recovered.taskId(), executionId(state.runId(), "task-complete"));
        VortexClient.TaskView completed = client.getTask(recovered.taskId());

        System.out.println();
        System.out.println("FINAL TASK: status=" + completed.status()
                + ", nodeCount=" + completed.nodeCount()
                + ", resumedCheckpointId=" + resumedCheckpoint
                + ", finalCheckpointId=" + completed.latestCheckpointId());
        System.out.println("DEMO COMPLETE: real model + real tools + Vortex memory + checkpoint recovery.");
    }

    private static Assistant agent(
            ChatModel chatModel,
            VortexMemoryTransformer transformer,
            AgentTools tools) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .systemMessage("Respond in Simplified Chinese. You are a release inspection agent. "
                        + "Tool calls are mandatory when requested. "
                        + "Treat Vortex memory as private durable context and quote tool evidence accurately.")
                .chatRequestTransformer(transformer)
                .tools(tools)
                .maxToolCallingRoundTrips(4)
                .build();
    }

    private static void requireTools(AgentTools tools, String... requiredTools) {
        List<String> invocations = tools.invocations();
        for (String requiredTool : requiredTools) {
            if (!invocations.contains(requiredTool)) {
                throw new IllegalStateException("The model did not call required tool " + requiredTool
                        + ". Use a model endpoint that supports OpenAI-compatible tool calling. Calls=" + invocations);
            }
        }
    }

    private static void requireMemory(VortexMemoryTransformer transformer) {
        boolean found = transformer.lastFragments().stream()
                .map(VortexClient.RecallFragment::content)
                .anyMatch(content -> content.contains("Aurora Ledger") && content.contains("Lin-7"));
        if (!found) {
            throw new IllegalStateException("Vortex recall did not return the private demo facts.");
        }
    }

    private static void printRecalledMemory(VortexMemoryTransformer transformer) {
        System.out.println("VORTEX RECALL:");
        for (VortexClient.RecallFragment fragment : transformer.lastFragments()) {
            System.out.println("- fragmentId=" + fragment.fragmentId() + ", score=" + fragment.score());
            System.out.println("  " + fragment.content());
        }
    }

    private static String executionId(String runId, String operation) {
        return "real-agent-demo-" + runId + "-" + operation;
    }

    private interface Assistant {
        @UserMessage("{{it}}")
        String chat(String message);
    }

    private enum Mode {
        PHASE1,
        PHASE2
    }

    private record Config(
            Mode mode,
            URI vortexBaseUrl,
            String vortexToken,
            String modelBaseUrl,
            String modelApiKey,
            String modelName,
            int modelTimeoutSeconds,
            String runId,
            String namespace,
            Path stateFile,
            Path repositoryRoot) {

        static Config fromEnvironment() {
            Mode mode = Mode.valueOf(requiredEnv("DEMO_MODE").trim().toUpperCase(Locale.ROOT));
            String runId = env("DEMO_RUN_ID", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            String namespace = env("VORTEX_NAMESPACE",
                    "quickstart-real-agent-" + Instant.now().toEpochMilli() + "-" + runId);
            return new Config(
                    mode,
                    URI.create(env("VORTEX_BASE_URL", "http://127.0.0.1:8080")),
                    requiredEnv("VORTEX_SECURITY_BEARER_TOKEN"),
                    env("MODEL_BASE_URL", "https://api.deepseek.com/v1"),
                    requiredEnv("MODEL_API_KEY"),
                    env("MODEL_NAME", "deepseek-chat"),
                    Integer.parseInt(env("MODEL_TIMEOUT_SECONDS", "120")),
                    runId,
                    namespace,
                    Path.of(requiredEnv("DEMO_STATE_FILE")),
                    Path.of(env("DEMO_REPOSITORY_ROOT", ".")).toAbsolutePath().normalize());
        }

        private static String requiredEnv(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Required environment variable is missing: " + name);
            }
            return value;
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
