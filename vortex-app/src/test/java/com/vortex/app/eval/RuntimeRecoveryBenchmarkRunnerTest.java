package com.vortex.app.eval;

import com.vortex.app.runtime.ExecutionIdProperties;
import com.vortex.app.runtime.ExecutionIdService;
import com.vortex.app.runtime.InMemoryExecutionIdStore;
import com.vortex.common.model.ConversationMessage;
import com.vortex.common.model.ConversationState;
import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.DagGraph;
import com.vortex.common.model.LlmCallState;
import com.vortex.common.model.LlmCallStatus;
import com.vortex.common.model.TaskBranch;
import com.vortex.common.model.TaskState;
import com.vortex.common.model.ToolExecutionState;
import com.vortex.common.model.ToolExecutionStatus;
import com.vortex.common.serialization.JsonMapperFactory;
import com.vortex.kernel.snapshot.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeRecoveryBenchmarkRunnerTest {

    private final SnapshotService snapshotService = mock(SnapshotService.class);
    private final RuntimeRecoveryTaskCacheEvictor cacheEvictor = mock(RuntimeRecoveryTaskCacheEvictor.class);
    private final TestSnapshotRuntime runtime = new TestSnapshotRuntime();
    private RuntimeRecoveryBenchmarkRunner runner;

    @BeforeEach
    void setUp() {
        ExecutionIdService executionIdService = new ExecutionIdService(
                new InMemoryExecutionIdStore(),
                JsonMapperFactory.create(),
                new ExecutionIdProperties());
        runner = new RuntimeRecoveryBenchmarkRunner(snapshotService, executionIdService, cacheEvictor);
        wireSnapshotService();
    }

    @Test
    void runConfiguredBenchmarkShouldReportCoveredRecoverySuccessRate() {
        RuntimeRecoveryBenchmarkReport report = runner.runConfiguredBenchmark();

        assertThat(report.getTotalCases()).isGreaterThanOrEqualTo(30);
        assertThat(report.getPassedCases()).isEqualTo(report.getTotalCases());
        assertThat(report.getFailedCases()).isZero();
        assertThat(report.getSuccessRate()).isEqualTo(1.0d);
        assertThat(report.getSuccessDefinition()).contains("Execution-ID guarded work must not run twice");
        assertThat(report.getRandomSeed()).isEqualTo(20260629L);
        assertThat(report.getCoveredCapabilities())
                .contains("Task DAG checkpoint and recover")
                .contains("Application Execution ID replay idempotency")
                .contains("Tool failure runtime recovery")
                .contains("LLM timeout task-level retry recovery")
                .contains("Deterministic multi-task interleaving recovery");
        assertThat(report.getExcludedCapabilities())
                .contains("Full async memory extraction/summary/embedding/index pipeline recovery")
                .doesNotContain("Tool failure runtime recovery", "LLM timeout task-level resume");
        assertThat(report.getCategorySummaries())
                .extracting(RuntimeRecoveryBenchmarkReport.CategorySummary::getCategory)
                .containsExactlyInAnyOrder("Service restart", "Tool failure", "LLM exception", "State integrity", "Concurrency");
        assertThat(report.getCategorySummaries())
                .allSatisfy(summary -> {
                    assertThat(summary.getTotalCases()).isPositive();
                    assertThat(summary.getPassedCases()).isEqualTo(summary.getTotalCases());
                    assertThat(summary.getSuccessRate()).isEqualTo(1.0d);
                });
        assertThat(report.getResults())
                .extracting(RuntimeRecoveryBenchmarkReport.CaseResult::getCaseId)
                .contains("runtime-recovery-001", "runtime-recovery-030");
        assertThat(report.getResults())
                .extracting(RuntimeRecoveryBenchmarkReport.CaseResult::getCategory)
                .contains("Service restart", "Tool failure", "LLM exception", "State integrity", "Concurrency");
        assertThat(report.getResults())
                .allSatisfy(result -> assertThat(result.isPassed()).isTrue());
        assertThat(runtime.executionIdTaskNodeCount()).isGreaterThanOrEqualTo(1);
        verify(cacheEvictor, atLeast(30)).evict(eq(snapshotService), any());
    }
    private void wireSnapshotService() {
        when(snapshotService.createTask(any(), any())).thenAnswer(invocation ->
                runtime.createTask(invocation.getArgument(0), invocation.getArgument(1)));
        when(snapshotService.appendNode(any(), any(), any())).thenAnswer(invocation ->
                runtime.appendNode(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(snapshotService.appendNodeWithTarget(any(), any(), any(), any(), any())).thenAnswer(invocation ->
                runtime.appendNodeWithTarget(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4)));
        when(snapshotService.completeNode(any(), any(), any())).thenAnswer(invocation ->
                runtime.completeNode(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(snapshotService.checkpoint(any())).thenAnswer(invocation ->
                runtime.checkpoint(invocation.getArgument(0)));
        when(snapshotService.recover(any(), any())).thenAnswer(invocation ->
                runtime.recover(invocation.getArgument(0), invocation.getArgument(1)));
        when(snapshotService.createBranch(any(), any(), any())).thenAnswer(invocation ->
                runtime.createBranch(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(snapshotService.mergeBranch(any(), any(), any())).thenAnswer(invocation ->
                runtime.mergeBranch(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(snapshotService.getTask(any())).thenAnswer(invocation ->
                Optional.ofNullable(runtime.tasks.get(invocation.getArgument(0))));
        when(snapshotService.appendConversationMessage(any(), any(), any(), any())).thenAnswer(invocation ->
                runtime.appendConversationMessage(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
        when(snapshotService.startToolExecution(any(), any(), any(), any())).thenAnswer(invocation ->
                runtime.startToolExecution(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
        when(snapshotService.completeToolExecution(any(), any(), any())).thenAnswer(invocation ->
                runtime.completeToolExecution(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(snapshotService.failToolExecution(any(), any(), any())).thenAnswer(invocation ->
                runtime.failToolExecution(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(snapshotService.startLlmCall(any(), any(), any(), any(), any(), anyLong())).thenAnswer(invocation ->
                runtime.startLlmCall(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5)));
        when(snapshotService.completeLlmCall(any(), any(), any())).thenAnswer(invocation ->
                runtime.completeLlmCall(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(snapshotService.timeoutLlmCall(any(), any(), any())).thenAnswer(invocation ->
                runtime.timeoutLlmCall(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(snapshotService.markLlmCallRetry(any(), any())).thenAnswer(invocation ->
                runtime.markLlmCallRetry(invocation.getArgument(0), invocation.getArgument(1)));
        doAnswer(invocation -> {
            runtime.updateContext(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(snapshotService).updateContext(any(), any(), any());
        doAnswer(invocation -> {
            runtime.deleteNode(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(snapshotService).deleteNode(any(), any());
        doAnswer(invocation -> {
            runtime.evict(invocation.getArgument(1));
            return null;
        }).when(cacheEvictor).evict(eq(snapshotService), any());
    }

    private static final class TestSnapshotRuntime {
        private final Map<String, TaskState> tasks = new LinkedHashMap<>();
        private final Map<String, TaskState> checkpoints = new HashMap<>();
        private final Map<String, String> latestCheckpointByTask = new HashMap<>();
        private long sequence;
        private final Map<String, List<RuntimeMutation>> mutationsByTask = new HashMap<>();

        TaskState createTask(String description, String namespace) {
            TaskState task = TaskState.builder()
                    .taskId(UUID.randomUUID().toString())
                    .description(description)
                    .namespace(namespace)
                    .graph(new DagGraph())
                    .createdAt(Instant.now())
                    .build();
            tasks.put(task.getTaskId(), task);
            return task;
        }

        DagNode appendNode(String taskId, String type, String content) {
            TaskState task = requireTask(taskId);
            DagNode node = DagNode.builder()
                    .nodeId(UUID.randomUUID().toString())
                    .type(DagNode.NodeType.valueOf(type))
                    .content(content)
                    .metadata(branchMetadata(task.getCurrentBranchId()))
                    .status(DagNode.NodeStatus.PENDING)
                    .build();
            task.getGraph().addNode(node);
            task.setCurrentNodeId(node.getNodeId());
            DagNode replayNode = copyNode(node);
            record(task, state -> {
                state.getGraph().addNode(copyNode(replayNode));
                state.setCurrentNodeId(replayNode.getNodeId());
            });
            return node;
        }

        DagNode appendNodeWithTarget(
                String taskId,
                String type,
                String content,
                String targetNodeId,
                DagEdge.EdgeType edgeType) {
            DagNode node = appendNode(taskId, type, content);
            TaskState task = requireTask(taskId);
            DagEdge edge = DagEdge.builder()
                    .sourceNodeId(targetNodeId)
                    .targetNodeId(node.getNodeId())
                    .dependencyType(edgeType)
                    .build();
            task.getGraph().addEdge(edge);
            DagEdge replayEdge = copyEdge(edge);
            record(task, state -> state.getGraph().addEdge(copyEdge(replayEdge)));
            return node;
        }

        DagNode completeNode(String taskId, String nodeId, String result) {
            TaskState task = requireTask(taskId);
            DagNode node = task.getGraph().getNode(nodeId).orElseThrow();
            node.complete(result);
            record(task, state -> state.getGraph().getNode(nodeId).orElseThrow().complete(result));
            return node;
        }

        void deleteNode(String taskId, String nodeId) {
            TaskState task = requireTask(taskId);
            task.getGraph().removeNode(nodeId);
            if (nodeId.equals(task.getCurrentNodeId())) {
                task.setCurrentNodeId(task.getGraph().getSinkNodes().stream()
                        .findFirst()
                        .map(DagNode::getNodeId)
                        .orElse(null));
            }
            record(task, state -> {
                state.getGraph().removeNode(nodeId);
                if (nodeId.equals(state.getCurrentNodeId())) {
                    state.setCurrentNodeId(state.getGraph().getSinkNodes().stream()
                            .findFirst()
                            .map(DagNode::getNodeId)
                            .orElse(null));
                }
            });
        }
        void updateContext(String taskId, String key, String value) {
            TaskState task = requireTask(taskId);
            if (value == null) {
                task.getContext().remove(key);
            } else {
                task.getContext().put(key, value);
            }
            record(task, state -> {
                if (value == null) {
                    state.getContext().remove(key);
                } else {
                    state.getContext().put(key, value);
                }
            });
        }
        String checkpoint(String taskId) {
            TaskState task = requireTask(taskId);
            String checkpointId = UUID.randomUUID().toString();
            task.setLatestCheckpointId(checkpointId);
            task.setLastCheckpointAt(Instant.now());
            checkpoints.put(checkpointId, copy(task));
            latestCheckpointByTask.put(taskId, checkpointId);
            return checkpointId;
        }

        TaskState recover(String taskId, String checkpointId) {
            String resolvedCheckpointId = checkpointId == null ? latestCheckpointByTask.get(taskId) : checkpointId;
            TaskState recovered = copy(checkpoints.get(resolvedCheckpointId));
            long checkpointSequence = recovered.getWalSequenceNumber();
            mutationsByTask.getOrDefault(taskId, List.of()).stream()
                    .filter(mutation -> mutation.sequence() > checkpointSequence)
                    .forEach(mutation -> mutation.apply(recovered));
            recovered.setLatestCheckpointId(resolvedCheckpointId);
            tasks.put(taskId, recovered);
            return recovered;
        }

        TaskBranch createBranch(String taskId, String branchName, String sourceNodeId) {
            TaskState task = requireTask(taskId);
            String branchId = UUID.randomUUID().toString();
            DagNode fork = DagNode.builder()
                    .nodeId(UUID.randomUUID().toString())
                    .type(DagNode.NodeType.FORK)
                    .content("Fork: " + branchName)
                    .status(DagNode.NodeStatus.COMPLETED)
                    .metadata(branchMetadata(branchId))
                    .build();
            task.getGraph().addNode(fork);
            task.getGraph().addEdge(DagEdge.builder()
                    .sourceNodeId(sourceNodeId)
                    .targetNodeId(fork.getNodeId())
                    .dependencyType(DagEdge.EdgeType.BRANCH)
                    .condition(branchName)
                    .build());
            TaskBranch branch = TaskBranch.builder()
                    .branchId(branchId)
                    .branchName(branchName)
                    .sourceNodeId(sourceNodeId)
                    .forkNodeId(fork.getNodeId())
                    .status(TaskBranch.BranchStatus.ACTIVE)
                    .build();
            task.getBranches().add(branch);
            task.setCurrentBranchId(branchId);
            task.setCurrentNodeId(fork.getNodeId());
            bump(task);
            return branch;
        }

        TaskBranch mergeBranch(String taskId, String sourceBranchId, String targetBranchId) {
            TaskState task = requireTask(taskId);
            TaskBranch source = task.getBranches().stream()
                    .filter(branch -> sourceBranchId.equals(branch.getBranchId()))
                    .findFirst()
                    .orElseThrow();
            TaskBranch target = task.getBranches().stream()
                    .filter(branch -> targetBranchId.equals(branch.getBranchId()))
                    .findFirst()
                    .orElseThrow();
            source.markMerged(targetBranchId);
            task.setCurrentBranchId(targetBranchId);
            DagNode mergeNode = DagNode.builder()
                    .nodeId(UUID.randomUUID().toString())
                    .type(DagNode.NodeType.MERGE)
                    .content("Merged branch")
                    .status(DagNode.NodeStatus.COMPLETED)
                    .metadata(branchMetadata(targetBranchId))
                    .build();
            task.getGraph().addNode(mergeNode);
            task.setCurrentNodeId(mergeNode.getNodeId());
            bump(task);
            return target;
        }

        ConversationMessage appendConversationMessage(String taskId, String conversationId, String role, String content) {
            TaskState task = requireTask(taskId);
            ConversationMessage message = ConversationMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .role(role)
                    .content(content)
                    .createdAt(Instant.now())
                    .build();
            appendConversation(task, conversationId, message);
            record(task, state -> appendConversation(state, conversationId, copyMessage(message)));
            return message;
        }

        ToolExecutionState startToolExecution(String taskId, String executionId, String toolName, String input) {
            TaskState task = requireTask(taskId);
            ToolExecutionState toolExecution = ToolExecutionState.builder()
                    .executionId(executionId)
                    .toolName(toolName)
                    .input(input)
                    .status(ToolExecutionStatus.RUNNING)
                    .startedAt(Instant.now())
                    .build();
            task.getToolExecutions().put(executionId, toolExecution);
            ToolExecutionState replayToolExecution = copyToolExecution(toolExecution);
            record(task, state -> state.getToolExecutions().put(executionId, copyToolExecution(replayToolExecution)));
            return toolExecution;
        }

        ToolExecutionState completeToolExecution(String taskId, String executionId, String output) {
            TaskState task = requireTask(taskId);
            ToolExecutionState toolExecution = task.getToolExecutions().get(executionId);
            toolExecution.succeed(output, Instant.now());
            record(task, state -> state.getToolExecutions().get(executionId).succeed(output, toolExecution.getCompletedAt()));
            return toolExecution;
        }
        ToolExecutionState failToolExecution(String taskId, String executionId, String errorMessage) {
            TaskState task = requireTask(taskId);
            ToolExecutionState toolExecution = task.getToolExecutions().get(executionId);
            toolExecution.fail(errorMessage, Instant.now());
            record(task, state -> state.getToolExecutions().get(executionId).fail(errorMessage, toolExecution.getCompletedAt()));
            return toolExecution;
        }

        LlmCallState startLlmCall(
                String taskId,
                String callId,
                String provider,
                String model,
                String prompt,
                long timeoutMillis) {
            TaskState task = requireTask(taskId);
            LlmCallState llmCall = LlmCallState.builder()
                    .callId(callId)
                    .provider(provider)
                    .model(model)
                    .prompt(prompt)
                    .timeoutMillis(timeoutMillis)
                    .status(LlmCallStatus.RUNNING)
                    .startedAt(Instant.now())
                    .build();
            task.getLlmCalls().put(callId, llmCall);
            LlmCallState replayLlmCall = copyLlmCall(llmCall);
            record(task, state -> state.getLlmCalls().put(callId, copyLlmCall(replayLlmCall)));
            return llmCall;
        }

        LlmCallState completeLlmCall(String taskId, String callId, String response) {
            TaskState task = requireTask(taskId);
            LlmCallState llmCall = task.getLlmCalls().get(callId);
            llmCall.complete(response, Instant.now());
            record(task, state -> state.getLlmCalls().get(callId).complete(response, llmCall.getCompletedAt()));
            return llmCall;
        }
        LlmCallState timeoutLlmCall(String taskId, String callId, String errorMessage) {
            TaskState task = requireTask(taskId);
            LlmCallState llmCall = task.getLlmCalls().get(callId);
            llmCall.timeout(errorMessage, Instant.now());
            record(task, state -> state.getLlmCalls().get(callId).timeout(errorMessage, llmCall.getCompletedAt()));
            return llmCall;
        }

        LlmCallState markLlmCallRetry(String taskId, String callId) {
            TaskState task = requireTask(taskId);
            LlmCallState llmCall = task.getLlmCalls().get(callId);
            llmCall.markRetryPending();
            record(task, state -> state.getLlmCalls().get(callId).markRetryPending());
            return llmCall;
        }
        void evict(String taskId) {
            TaskState current = tasks.get(taskId);
            if (current != null && current.getLatestCheckpointId() != null) {
                tasks.remove(taskId);
            }
        }

        int executionIdTaskNodeCount() {
            return tasks.values().stream()
                    .filter(task -> task.getDescription().contains("execution id case"))
                    .mapToInt(task -> task.getGraph().nodeCount())
                    .sum();
        }

        private TaskState requireTask(String taskId) {
            TaskState task = tasks.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("Task not loaded: " + taskId);
            }
            return task;
        }

        private void bump(TaskState task) {
            task.setWalSequenceNumber(++sequence);
        }

        private void record(TaskState task, RuntimeMutationApplier applier) {
            bump(task);
            mutationsByTask.computeIfAbsent(task.getTaskId(), ignored -> new ArrayList<>())
                    .add(new RuntimeMutation(task.getWalSequenceNumber(), applier));
        }

        private void appendConversation(TaskState task, String conversationId, ConversationMessage message) {
            ConversationState conversation = task.getConversations().computeIfAbsent(conversationId,
                    id -> ConversationState.builder()
                            .conversationId(id)
                            .title(id)
                            .createdAt(message.getCreatedAt())
                            .updatedAt(message.getCreatedAt())
                            .build());
            conversation.appendMessage(message);
        }

        private TaskState copy(TaskState source) {
            DagGraph graph = new DagGraph();
            source.getGraph().getNodes().values().forEach(node -> graph.addNode(copyNode(node)));
            graph.setEdges(source.getGraph().edgeSnapshot().stream()
                    .map(this::copyEdge)
                    .toList());
            return TaskState.builder()
                    .taskId(source.getTaskId())
                    .description(source.getDescription())
                    .namespace(source.getNamespace())
                    .status(source.getStatus())
                    .graph(graph)
                    .currentNodeId(source.getCurrentNodeId())
                    .currentBranchId(source.getCurrentBranchId())
                    .branches(new ArrayList<>(source.getBranches()))
                    .walSequenceNumber(source.getWalSequenceNumber())
                    .referencedFragmentIds(new ArrayList<>(source.getReferencedFragmentIds()))
                    .context(new HashMap<>(source.getContext()))
                    .conversations(copyConversations(source.getConversations()))
                    .toolExecutions(copyToolExecutions(source.getToolExecutions()))
                    .llmCalls(copyLlmCalls(source.getLlmCalls()))
                    .createdAt(source.getCreatedAt())
                    .lastCheckpointAt(source.getLastCheckpointAt())
                    .latestCheckpointId(source.getLatestCheckpointId())
                    .finalizationStatus(source.getFinalizationStatus())
                    .build();
        }

        private DagNode copyNode(DagNode source) {
            return DagNode.builder()
                    .nodeId(source.getNodeId())
                    .type(source.getType())
                    .content(source.getContent())
                    .result(source.getResult())
                    .status(source.getStatus())
                    .metadata(new HashMap<>(source.getMetadata()))
                    .createdAt(source.getCreatedAt())
                    .executedAt(source.getExecutedAt())
                    .completedAt(source.getCompletedAt())
                    .build();
        }

        private DagEdge copyEdge(DagEdge source) {
            return DagEdge.builder()
                    .edgeId(source.getEdgeId())
                    .sourceNodeId(source.getSourceNodeId())
                    .targetNodeId(source.getTargetNodeId())
                    .dependencyType(source.getDependencyType())
                    .condition(source.getCondition())
                    .weight(source.getWeight())
                    .build();
        }
        private Map<String, ConversationState> copyConversations(Map<String, ConversationState> source) {
            Map<String, ConversationState> copy = new HashMap<>();
            source.forEach((id, conversation) -> copy.put(id, ConversationState.builder()
                    .conversationId(conversation.getConversationId())
                    .title(conversation.getTitle())
                    .messages(conversation.getMessages().stream().map(this::copyMessage).toList())
                    .createdAt(conversation.getCreatedAt())
                    .updatedAt(conversation.getUpdatedAt())
                    .metadata(new HashMap<>(conversation.getMetadata()))
                    .build()));
            return copy;
        }

        private ConversationMessage copyMessage(ConversationMessage source) {
            return ConversationMessage.builder()
                    .messageId(source.getMessageId())
                    .role(source.getRole())
                    .content(source.getContent())
                    .createdAt(source.getCreatedAt())
                    .metadata(new HashMap<>(source.getMetadata()))
                    .build();
        }

        private Map<String, ToolExecutionState> copyToolExecutions(Map<String, ToolExecutionState> source) {
            Map<String, ToolExecutionState> copy = new HashMap<>();
            source.forEach((id, toolExecution) -> copy.put(id, copyToolExecution(toolExecution)));
            return copy;
        }

        private ToolExecutionState copyToolExecution(ToolExecutionState source) {
            return ToolExecutionState.builder()
                    .executionId(source.getExecutionId())
                    .toolName(source.getToolName())
                    .input(source.getInput())
                    .output(source.getOutput())
                    .errorMessage(source.getErrorMessage())
                    .status(source.getStatus())
                    .startedAt(source.getStartedAt())
                    .completedAt(source.getCompletedAt())
                    .metadata(new HashMap<>(source.getMetadata()))
                    .build();
        }

        private Map<String, LlmCallState> copyLlmCalls(Map<String, LlmCallState> source) {
            Map<String, LlmCallState> copy = new HashMap<>();
            source.forEach((id, llmCall) -> copy.put(id, copyLlmCall(llmCall)));
            return copy;
        }

        private LlmCallState copyLlmCall(LlmCallState source) {
            return LlmCallState.builder()
                    .callId(source.getCallId())
                    .provider(source.getProvider())
                    .model(source.getModel())
                    .prompt(source.getPrompt())
                    .response(source.getResponse())
                    .errorMessage(source.getErrorMessage())
                    .status(source.getStatus())
                    .attempt(source.getAttempt())
                    .timeoutMillis(source.getTimeoutMillis())
                    .retryable(source.isRetryable())
                    .startedAt(source.getStartedAt())
                    .completedAt(source.getCompletedAt())
                    .metadata(new HashMap<>(source.getMetadata()))
                    .build();
        }
        private Map<String, String> branchMetadata(String branchId) {
            if (branchId == null || branchId.isBlank()) {
                return Map.of();
            }
            return Map.of("branchId", branchId);
        }

        private record RuntimeMutation(long sequence, RuntimeMutationApplier applier) {
            void apply(TaskState state) {
                applier.apply(state);
                state.setWalSequenceNumber(sequence);
            }
        }

        @FunctionalInterface
        private interface RuntimeMutationApplier {
            void apply(TaskState state);
        }
    }
}

