package com.vortex.examples.realagent;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RealAgentDemo {

    private static final int TOTAL_STEPS = 5;
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

        DemoConsole.banner(config.modelName(), config.vortexBaseUrl().toString(), config.mode().name());
        if (config.mode() == Mode.PHASE1) {
            runPhaseOne(config, client, chatModel);
        } else {
            runPhaseTwo(config, client, chatModel);
        }
    }

    private static void runPhaseOne(Config config, VortexClient client, ChatModel chatModel) throws Exception {
        String runId = config.runId();
        String namespace = config.namespace();

        DemoConsole.section(1, TOTAL_STEPS, "Store private facts in Vortex durable memory");
        DemoConsole.event("VORTEX", "Namespace: " + namespace);
        int stored = client.store(PRIVATE_FACTS, namespace, List.of("real-agent-demo", "private-release-facts"));
        DemoConsole.event("VORTEX", "Stored fragments: " + stored);

        DemoConsole.section(2, TOTAL_STEPS, "Compare the same model without and with Vortex");
        String question = "What are the private deployment codename, freeze time, and approval owner for this demo?";
        Assistant baseline = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .systemMessage("Respond in Simplified Chinese. Answer only from information available in this new "
                        + "conversation. If facts are missing, say so.")
                .build();
        DemoConsole.event("MODEL", "Calling the real model without Vortex memory...");
        String baselineAnswer = baseline.chat(question);
        DemoConsole.block("BEFORE: MODEL WITHOUT VORTEX", baselineAnswer);

        VortexClient.TaskView task = client.createTask(
                "Real model and tool agent crash recovery demo", namespace, executionId(runId, "task-create"));
        AgentTools tools = new AgentTools(config.repositoryRoot(), client, null);
        VortexMemoryTransformer transformer = new VortexMemoryTransformer(client, namespace, 5, 768);
        Assistant agent = agent(chatModel, transformer, tools);

        DemoConsole.event("AGENT", "Calling the same model with Vortex memory and mandatory tools...");
        String answer = agent.chat("Before answering, you MUST call inspectRepository and inspectVortexHealth. "
                + "Then produce a concise phase-one release report containing the private deployment codename, "
                + "freeze time, approval owner, repository evidence, and Vortex health. "
                + "End with exactly: PHASE_ONE_COMPLETE");

        requireTools(tools, "inspectRepository", "inspectVortexHealth");
        requireMemory(transformer);
        DemoConsole.memory(transformer.lastFragments());
        DemoConsole.block("AFTER: REAL AGENT WITH VORTEX + TOOLS", answer);

        DemoConsole.section(3, TOTAL_STEPS, "Persist task state, then simulate a hard crash");
        VortexClient.NodeView node = client.appendNode(task.taskId(), "ACTION", answer,
                executionId(runId, "phase-one-node"));
        client.completeNode(task.taskId(), node.nodeId(), "PHASE_ONE_COMPLETE",
                executionId(runId, "phase-one-node-complete"));
        String checkpointId = client.checkpoint(task.taskId(), executionId(runId, "phase-one-checkpoint"));
        new DemoState(runId, namespace, task.taskId(), checkpointId, node.nodeId())
                .writeAtomically(config.stateFile());

        DemoConsole.checkpoint(checkpointId, 1,
                "The orchestrator will now kill this JVM. Only durable Vortex state will survive.");
        DemoConsole.event("PROCESS", "Phase-one JVM is waiting for forced termination...");
        System.out.flush();
        while (true) {
            Thread.sleep(5_000);
        }
    }

    private static void runPhaseTwo(Config config, VortexClient client, ChatModel chatModel) throws Exception {
        DemoState state = DemoState.read(config.stateFile());
        DemoConsole.section(4, TOTAL_STEPS, "Start a new JVM and recover the durable checkpoint");
        VortexClient.TaskView recovered = client.recover(
                state.taskId(), state.checkpointId(), executionId(state.runId(), "recover"));
        if (recovered.nodeCount() < 1) {
            throw new IllegalStateException("Recovered task does not contain the phase-one node.");
        }
        DemoConsole.event("RECOVERY", "New process recovered the old task. Phase one was not repeated.");
        DemoConsole.task(recovered);

        AgentTools tools = new AgentTools(config.repositoryRoot(), client, recovered.taskId());
        VortexMemoryTransformer transformer = new VortexMemoryTransformer(client, state.namespace(), 5, 768);
        Assistant agent = agent(chatModel, transformer, tools);
        DemoConsole.event("AGENT", "Verifying recovered state with tools and durable memory...");
        String answer = agent.chat("This process started after the phase-one process was killed. "
                + "Before answering, you MUST call inspectRecoveredTask and inspectRepository. "
                + "Use Vortex durable memory and recovered task evidence to produce a concise continuation report. "
                + "State that phase one was not repeated, include the private deployment codename and approval owner, "
                + "and end with exactly: RECOVERY_COMPLETE");

        requireTools(tools, "inspectRecoveredTask", "inspectRepository");
        requireMemory(transformer);
        DemoConsole.memory(transformer.lastFragments());
        DemoConsole.block("RECOVERED AGENT ANSWER", answer);

        VortexClient.NodeView recoveryNode = client.appendNode(recovered.taskId(), "ACTION", answer,
                executionId(state.runId(), "phase-two-node"));
        client.completeNode(recovered.taskId(), recoveryNode.nodeId(), "RECOVERY_COMPLETE",
                executionId(state.runId(), "phase-two-node-complete"));
        String latestCheckpoint = client.checkpoint(
                recovered.taskId(), executionId(state.runId(), "phase-two-checkpoint"));
        VortexClient.TaskView afterRecovery = client.getTask(recovered.taskId());
        DemoConsole.checkpoint(latestCheckpoint, afterRecovery.nodeCount(),
                "The new JVM continued from the recovered task and appended a new node.");

        DemoConsole.section(5, TOTAL_STEPS, "Interact with the recovered Agent");
        if (config.interactive()) {
            latestCheckpoint = runInteractiveConsole(
                    client, state, recovered.taskId(), agent, transformer, latestCheckpoint);
        } else {
            DemoConsole.event("DEMO", "Interactive mode is disabled for this non-interactive run.");
        }

        client.completeTask(recovered.taskId(), executionId(state.runId(), "task-complete"));
        VortexClient.TaskView completed = client.getTask(recovered.taskId());
        DemoConsole.task(completed);
        DemoConsole.success("DEMO COMPLETE: real model + tools + memory + crash recovery + live interaction.");
        DemoConsole.event("FINAL", "Last interactive checkpoint: " + latestCheckpoint);
    }

    private static String runInteractiveConsole(
            VortexClient client,
            DemoState state,
            String taskId,
            Assistant agent,
            VortexMemoryTransformer transformer,
            String latestCheckpoint) throws Exception {
        DemoConsole.interactiveHelp();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int turn = 1;
        while (true) {
            DemoConsole.prompt();
            String input = reader.readLine();
            if (input == null) {
                DemoConsole.event("INPUT", "Console input closed; finishing the task.");
                return latestCheckpoint;
            }
            input = input.strip();
            if (input.isEmpty()) {
                continue;
            }

            String command = input.toLowerCase(Locale.ROOT);
            if ("/exit".equals(command)) {
                DemoConsole.event("INPUT", "Exiting the live console and completing the Vortex task.");
                return latestCheckpoint;
            }
            if ("/help".equals(command)) {
                DemoConsole.interactiveHelp();
                continue;
            }
            if ("/status".equals(command)) {
                DemoConsole.task(client.getTask(taskId));
                continue;
            }
            if ("/memory".equals(command)) {
                DemoConsole.memory(client.recall(
                        "private deployment codename freeze time approval owner crash recovery",
                        state.namespace(), 5, 768));
                continue;
            }

            DemoConsole.event("USER", input);
            String answer = agent.chat("Answer the user's live question in Simplified Chinese. "
                    + "Use the available tools when the question asks for repository, Vortex health, or recovered task "
                    + "evidence. Use recalled durable memory when relevant. User question: " + input);
            DemoConsole.memory(transformer.lastFragments());
            DemoConsole.block("RECOVERED AGENT", answer);

            String turnKey = "interactive-" + turn;
            VortexClient.NodeView node = client.appendNode(taskId, "ACTION",
                    "USER: " + input + System.lineSeparator() + "AGENT: " + answer,
                    executionId(state.runId(), turnKey + "-node"));
            client.completeNode(taskId, node.nodeId(), "INTERACTIVE_TURN_COMPLETE",
                    executionId(state.runId(), turnKey + "-node-complete"));
            latestCheckpoint = client.checkpoint(taskId,
                    executionId(state.runId(), turnKey + "-checkpoint"));
            VortexClient.TaskView task = client.getTask(taskId);
            DemoConsole.checkpoint(latestCheckpoint, task.nodeCount(),
                    "Interactive turn " + turn + " is durable and recoverable.");
            turn++;
        }
    }

    private static Assistant agent(
            ChatModel chatModel,
            VortexMemoryTransformer transformer,
            AgentTools tools) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .systemMessage("Respond in Simplified Chinese. You are a release inspection agent running in an "
                        + "interactive Vortex recovery demo. Tool calls are mandatory when requested. Treat Vortex "
                        + "memory as private durable context and quote tool evidence accurately.")
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
            Path repositoryRoot,
            boolean interactive) {

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
                    Path.of(env("DEMO_REPOSITORY_ROOT", ".")).toAbsolutePath().normalize(),
                    Boolean.parseBoolean(env("DEMO_INTERACTIVE", "false")));
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
