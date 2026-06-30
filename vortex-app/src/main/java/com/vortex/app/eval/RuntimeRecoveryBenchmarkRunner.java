package com.vortex.app.eval;

import com.vortex.app.runtime.ExecutionIdService;
import com.vortex.common.model.ConversationMessage;
import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.LlmCallState;
import com.vortex.common.model.LlmCallStatus;
import com.vortex.common.model.TaskBranch;
import com.vortex.common.model.TaskState;
import com.vortex.common.model.ToolExecutionState;
import com.vortex.common.model.ToolExecutionStatus;
import com.vortex.kernel.snapshot.SnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeRecoveryBenchmarkRunner {

    private static final long RANDOM_SEED = 20260629L;
    private static final String CATEGORY_SERVICE_RESTART = "Service restart";
    private static final String CATEGORY_TOOL_FAILURE = "Tool failure";
    private static final String CATEGORY_LLM_EXCEPTION = "LLM exception";
    private static final String CATEGORY_STATE_INTEGRITY = "State integrity";
    private static final String CATEGORY_CONCURRENCY = "Concurrency";
    private static final String SUCCESS_DEFINITION = "A case passes only when recovered Task/DAG/context/conversation/memory reference/tool/LLM state matches the expected pre-crash or WAL-replayed state; completed Execution-ID guarded work must not run twice, and running work must remain resumable.";

    private static final List<String> COVERED_CAPABILITIES = List.of(
            "Task DAG checkpoint and recover",
            "Recovery after process-local task cache eviction",
            "Repeated recover idempotency",
            "Branch and merge state recovery",
            "Application Execution ID replay idempotency",
            "Conversation state snapshot and recovery",
            "Tool failure runtime recovery",
            "LLM timeout task-level retry recovery",
            "Referenced memory-fragment state recovery",
            "Tool running/completed/failed state recovery",
            "LLM running/completed/timeout/retry state recovery",
            "Deterministic multi-task interleaving recovery");

    private static final List<String> EXCLUDED_CAPABILITIES = List.of(
            "External process-manager crash-loop orchestration",
            "Cross-binary historical snapshot schema migration",
            "Full async memory extraction/summary/embedding/index pipeline recovery");

    private final SnapshotService snapshotService;
    private final ExecutionIdService executionIdService;
    private final RuntimeRecoveryTaskCacheEvictor cacheEvictor;

    public RuntimeRecoveryBenchmarkReport runConfiguredBenchmark() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        List<RuntimeRecoveryBenchmarkReport.CaseResult> results = benchmarkCases(runId).stream()
                .map(this::runCase)
                .toList();

        int passed = (int) results.stream().filter(RuntimeRecoveryBenchmarkReport.CaseResult::isPassed).count();
        int total = results.size();
        long totalLatency = results.stream()
                .mapToLong(RuntimeRecoveryBenchmarkReport.CaseResult::getLatencyMs)
                .sum();
        return RuntimeRecoveryBenchmarkReport.builder()
                .generatedAt(Instant.now())
                .runId(runId)
                .totalCases(total)
                .passedCases(passed)
                .failedCases(total - passed)
                .successRate(total == 0 ? 0.0d : (double) passed / total)
                .totalLatencyMs(totalLatency)
                .averageLatencyMs(total == 0 ? 0.0d : (double) totalLatency / total)
                .successDefinition(SUCCESS_DEFINITION)
                .randomSeed(RANDOM_SEED)
                .coveredCapabilities(COVERED_CAPABILITIES)
                .excludedCapabilities(EXCLUDED_CAPABILITIES)
                .categorySummaries(categorySummaries(results))
                .results(results)
                .build();
    }
    private List<BenchmarkCase> benchmarkCases(String runId) {
        List<BenchmarkCase> cases = new ArrayList<>();
        addCase(cases, "Checkpoint recovery after cache eviction", CATEGORY_SERVICE_RESTART,
                "checkpoint-recover", () -> checkpointRecoveryAfterCacheEviction(runId));
        addCase(cases, "Repeated recovery idempotency", CATEGORY_SERVICE_RESTART,
                "repeated-recover-idempotency", () -> repeatedRecoverIdempotency(runId));
        addCase(cases, "Restart replays post-checkpoint WAL", CATEGORY_SERVICE_RESTART,
                "wal-replay-after-restart", () -> walReplayAfterCheckpoint(runId));
        addCase(cases, "Recover then continue appending DAG", CATEGORY_SERVICE_RESTART,
                "recover-then-continue", () -> recoverThenContinue(runId));
        addCase(cases, "Latest checkpoint recovery without explicit checkpoint ID", CATEGORY_SERVICE_RESTART,
                "latest-checkpoint-recover", () -> latestCheckpointRecovery(runId));
        addCase(cases, "Second crash after WAL replay recovers same state", CATEGORY_SERVICE_RESTART,
                "second-crash-after-replay", () -> secondCrashAfterReplay(runId));

        addCase(cases, "Execution ID replay idempotency", CATEGORY_TOOL_FAILURE,
                "execution-id-replay", () -> executionIdReplayIdempotency(runId));
        addCase(cases, "Tool failure state recovery", CATEGORY_TOOL_FAILURE,
                "tool-failure-recover", () -> toolFailureRecovery(runId));
        addCase(cases, "Tool timeout failure recovery", CATEGORY_TOOL_FAILURE,
                "tool-timeout-failure-recover", () -> toolFailureVariantRecovery(runId, "tool-timeout-main", "http", "GET /slow", "timeout after 2000ms"));
        addCase(cases, "Tool exception failure recovery", CATEGORY_TOOL_FAILURE,
                "tool-exception-failure-recover", () -> toolFailureVariantRecovery(runId, "tool-exception-main", "shell", "mvn test", "IllegalStateException: build failed"));
        addCase(cases, "Tool partial result failure recovery", CATEGORY_TOOL_FAILURE,
                "tool-partial-failure-recover", () -> toolFailureVariantRecovery(runId, "tool-partial-main", "retriever", "fetch shards", "partial result: 2/5 shards"));
        addCase(cases, "Running tool remains resumable after recovery", CATEGORY_TOOL_FAILURE,
                "tool-running-resume", () -> runningToolRecovery(runId));
        addCase(cases, "Completed tool result survives WAL replay", CATEGORY_TOOL_FAILURE,
                "tool-completed-recover", () -> completedToolRecovery(runId));
        addCase(cases, "Mixed completed and running tool states recover", CATEGORY_TOOL_FAILURE,
                "tool-mixed-state-recover", () -> mixedToolStateRecovery(runId));

        addCase(cases, "LLM timeout retry recovery", CATEGORY_LLM_EXCEPTION,
                "llm-timeout-retry-recover", () -> llmTimeoutRetryRecovery(runId));
        addCase(cases, "LLM rate limit retry recovery", CATEGORY_LLM_EXCEPTION,
                "llm-rate-limit-retry-recover", () -> llmTimeoutVariantRecovery(runId, "llm-rate-limit-main", "rate limit 429"));
        addCase(cases, "LLM truncated response recovery", CATEGORY_LLM_EXCEPTION,
                "llm-truncated-response-recover", () -> llmCompletedRecovery(runId, "llm-truncated-main", "TRUNCATED: first half only"));
        addCase(cases, "Running LLM call remains resumable", CATEGORY_LLM_EXCEPTION,
                "llm-running-resume", () -> runningLlmRecovery(runId));
        addCase(cases, "Completed LLM response recovers", CATEGORY_LLM_EXCEPTION,
                "llm-completed-recover", () -> llmCompletedRecovery(runId, "llm-complete-main", "recovery plan ready"));
        addCase(cases, "LLM timeout after checkpoint is replayed", CATEGORY_LLM_EXCEPTION,
                "llm-timeout-wal-replay", () -> llmTimeoutWalReplayRecovery(runId));

        addCase(cases, "Branch and merge recovery", CATEGORY_STATE_INTEGRITY,
                "branch-merge-recover", () -> branchMergeRecovery(runId));
        addCase(cases, "Conversation state recovery", CATEGORY_STATE_INTEGRITY,
                "conversation-state-recover", () -> conversationStateRecovery(runId));
        addCase(cases, "Memory reference list recovery", CATEGORY_STATE_INTEGRITY,
                "memory-reference-recover", () -> memoryReferenceRecovery(runId));
        addCase(cases, "Context removal WAL replay", CATEGORY_STATE_INTEGRITY,
                "context-removal-wal-replay", () -> contextRemovalRecovery(runId));
        addCase(cases, "DAG node deletion updates current node", CATEGORY_STATE_INTEGRITY,
                "dag-delete-node-recover", () -> dagDeleteNodeRecovery(runId));
        addCase(cases, "Special character payload recovery", CATEGORY_STATE_INTEGRITY,
                "special-payload-recover", () -> specialCharacterPayloadRecovery(runId));
        addCase(cases, "Empty optional runtime state remains initialized", CATEGORY_STATE_INTEGRITY,
                "empty-runtime-state-recover", () -> emptyRuntimeStateRecovery(runId));

        addCase(cases, "Multiple tasks recover in one run", CATEGORY_CONCURRENCY,
                "multi-task-recover", () -> multiTaskRecovery(runId));
        addCase(cases, "New request interleaved with recovery", CATEGORY_CONCURRENCY,
                "interleaved-new-request-recover", () -> interleavedNewRequestRecovery(runId));
        addCase(cases, "Repeated recovery across two tasks", CATEGORY_CONCURRENCY,
                "multi-task-repeated-recover", () -> multiTaskRepeatedRecovery(runId));
        addCase(cases, "Execution IDs stay isolated across tasks", CATEGORY_CONCURRENCY,
                "execution-id-cross-task-isolation", () -> executionIdCrossTaskIsolation(runId));
        addCase(cases, "Conversation recovery across multiple tasks", CATEGORY_CONCURRENCY,
                "multi-task-conversation-recover", () -> multiTaskConversationRecovery(runId));
        return cases;
    }

    private void addCase(
            List<BenchmarkCase> cases,
            String name,
            String category,
            String capability,
            Supplier<RuntimeRecoveryBenchmarkReport.CaseResult> action) {
        cases.add(new BenchmarkCase(
                "runtime-recovery-" + String.format("%03d", cases.size() + 1),
                name,
                category,
                capability,
                action));
    }
    private RuntimeRecoveryBenchmarkReport.CaseResult checkpointRecoveryAfterCacheEviction(String runId) {
        TaskState task = snapshotService.createTask(
                "runtime recovery benchmark checkpoint case " + runId,
                namespace(runId));
        DagNode plan = snapshotService.appendNode(task.getTaskId(), "THOUGHT", "plan recovery steps");
        snapshotService.updateContext(task.getTaskId(), "phase", "checkpointed");
        snapshotService.completeNode(task.getTaskId(), plan.getNodeId(), "plan-ready");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());

        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);

        boolean passed = recovered.getGraph().nodeCount() == 1
                && recovered.getGraph().edgeCount() == 0
                && "checkpointed".equals(recovered.getContext().get("phase"))
                && recovered.getGraph().getNode(plan.getNodeId())
                .map(node -> node.getStatus() == DagNode.NodeStatus.COMPLETED)
                .orElse(false)
                && recovered.getStatus() == TaskState.TaskStatus.RUNNING;
        return resultBuilder("checkpoint-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("1 completed node, phase=checkpointed, status=RUNNING")
                .actual("nodes=" + recovered.getGraph().nodeCount()
                        + ", phase=" + recovered.getContext().get("phase")
                        + ", status=" + recovered.getStatus())
                .details(Map.of(
                        "nodeId", plan.getNodeId(),
                        "currentNodeId", nullToEmpty(recovered.getCurrentNodeId()),
                        "walSequence", String.valueOf(recovered.getWalSequenceNumber())))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult repeatedRecoverIdempotency(String runId) {
        TaskState task = snapshotService.createTask(
                "runtime recovery benchmark repeated case " + runId,
                namespace(runId));
        DagNode root = snapshotService.appendNode(task.getTaskId(), "THOUGHT", "root");
        DagNode action = snapshotService.appendNodeWithTarget(
                task.getTaskId(),
                "ACTION",
                "execute idempotent step",
                root.getNodeId(),
                DagEdge.EdgeType.CONTROL_DEP);
        snapshotService.updateContext(task.getTaskId(), "attempt", "first");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());

        snapshotService.updateContext(task.getTaskId(), "attempt", "second");
        snapshotService.completeNode(task.getTaskId(), action.getNodeId(), "done-after-checkpoint");
        cacheEvictor.evict(snapshotService, task.getTaskId());

        TaskState first = snapshotService.recover(task.getTaskId(), checkpointId);
        RecoveryFingerprint firstFingerprint = fingerprint(first);
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState second = snapshotService.recover(task.getTaskId(), checkpointId);
        RecoveryFingerprint secondFingerprint = fingerprint(second);

        boolean passed = firstFingerprint.equals(secondFingerprint)
                && first.getGraph().nodeCount() == 2
                && first.getGraph().edgeCount() == 1
                && "second".equals(first.getContext().get("attempt"))
                && first.getGraph().getNode(action.getNodeId())
                .map(node -> node.getStatus() == DagNode.NodeStatus.COMPLETED)
                .orElse(false);
        return resultBuilder("repeated-recover-idempotency", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("two recoveries produce identical DAG/context and replay WAL once")
                .actual("first=" + firstFingerprint + ", second=" + secondFingerprint)
                .details(Map.of(
                        "rootNodeId", root.getNodeId(),
                        "actionNodeId", action.getNodeId(),
                        "contextAttempt", nullToEmpty(first.getContext().get("attempt"))))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult branchMergeRecovery(String runId) {
        TaskState task = snapshotService.createTask(
                "runtime recovery benchmark branch case " + runId,
                namespace(runId));
        DagNode root = snapshotService.appendNode(task.getTaskId(), "THOUGHT", "choose route");
        TaskBranch planA = snapshotService.createBranch(task.getTaskId(), "plan-a", root.getNodeId());
        TaskBranch planB = snapshotService.createBranch(task.getTaskId(), "plan-b", root.getNodeId());
        snapshotService.mergeBranch(task.getTaskId(), planB.getBranchId(), planA.getBranchId());
        snapshotService.updateContext(task.getTaskId(), "selectedPlan", "plan-a");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());

        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);

        boolean mergedBranchRecovered = recovered.getBranches().stream()
                .anyMatch(branch -> planB.getBranchId().equals(branch.getBranchId())
                        && branch.getStatus() == TaskBranch.BranchStatus.MERGED
                        && planA.getBranchId().equals(branch.getMergedIntoBranchId()));
        boolean activeBranchRecovered = planA.getBranchId().equals(recovered.getCurrentBranchId());
        boolean mergeNodeRecovered = recovered.getGraph().getNodes().values().stream()
                .anyMatch(node -> node.getType() == DagNode.NodeType.MERGE);
        boolean passed = recovered.getBranches().size() == 2
                && mergedBranchRecovered
                && activeBranchRecovered
                && mergeNodeRecovered
                && "plan-a".equals(recovered.getContext().get("selectedPlan"));
        return resultBuilder("branch-merge-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("2 branches, plan-b merged into plan-a, active branch=plan-a, merge node present")
                .actual("branches=" + recovered.getBranches().size()
                        + ", currentBranchId=" + recovered.getCurrentBranchId()
                        + ", mergeNode=" + mergeNodeRecovered
                        + ", selectedPlan=" + recovered.getContext().get("selectedPlan"))
                .details(Map.of(
                        "planABranchId", planA.getBranchId(),
                        "planBBranchId", planB.getBranchId(),
                        "nodeCount", String.valueOf(recovered.getGraph().nodeCount()),
                        "edgeCount", String.valueOf(recovered.getGraph().edgeCount())))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult executionIdReplayIdempotency(String runId) {
        TaskState task = snapshotService.createTask(
                "runtime recovery benchmark execution id case " + runId,
                namespace(runId));
        Map<String, Object> request = Map.of(
                "taskId", task.getTaskId(),
                "type", "THOUGHT",
                "content", "execution-id guarded append");
        String executionId = "runtime-benchmark-" + runId;

        ResponseEntity<Map<String, Object>> first = executeAppendWithExecutionId(task.getTaskId(), executionId, request);
        ResponseEntity<Map<String, Object>> replay = executeAppendWithExecutionId(task.getTaskId(), executionId, request);

        TaskState loaded = snapshotService.getTask(task.getTaskId()).orElseThrow();
        boolean replayed = "true".equals(replay.getHeaders().getFirst(ExecutionIdService.REPLAYED_HEADER_NAME));
        boolean sameBody = first.getBody() != null && first.getBody().equals(replay.getBody());
        boolean passed = loaded.getGraph().nodeCount() == 1 && replayed && sameBody;
        return resultBuilder("execution-id-replay", task.getTaskId(), null)
                .passed(passed)
                .expected("same Execution ID replays response and appends exactly one node")
                .actual("nodes=" + loaded.getGraph().nodeCount()
                        + ", replayed=" + replayed
                        + ", sameBody=" + sameBody)
                .details(Map.of(
                        "executionId", executionId,
                        "firstStatus", String.valueOf(first.getStatusCode().value()),
                        "replayStatus", String.valueOf(replay.getStatusCode().value())))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult conversationStateRecovery(String runId) {
        TaskState task = snapshotService.createTask(
                "runtime recovery benchmark conversation case " + runId,
                namespace(runId));
        ConversationMessage userMessage = snapshotService.appendConversationMessage(
                task.getTaskId(), "conversation-main", "user", "deployment failed after cache restart");
        snapshotService.appendConversationMessage(
                task.getTaskId(), "conversation-main", "assistant", "capture error and resume plan");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());

        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);

        boolean passed = recovered.getConversations().containsKey("conversation-main")
                && recovered.getConversations().get("conversation-main").getMessages().size() == 2
                && recovered.getConversations().get("conversation-main").getMessages().stream()
                .anyMatch(message -> userMessage.getMessageId().equals(message.getMessageId())
                        && "deployment failed after cache restart".equals(message.getContent()));
        return resultBuilder("conversation-state-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("conversation-main has 2 messages after recovery")
                .actual("conversationCount=" + recovered.getConversations().size()
                        + ", messageCount=" + recovered.getConversations()
                        .getOrDefault("conversation-main", com.vortex.common.model.ConversationState.builder().build())
                        .getMessages().size())
                .details(Map.of(
                        "conversationId", "conversation-main",
                        "userMessageId", userMessage.getMessageId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult toolFailureRecovery(String runId) {
        TaskState task = snapshotService.createTask(
                "runtime recovery benchmark tool failure case " + runId,
                namespace(runId));
        String checkpointId = snapshotService.checkpoint(task.getTaskId());

        ToolExecutionState failedTool = snapshotService.startToolExecution(
                task.getTaskId(), "tool-failure-main", "shell", "mvn test");
        snapshotService.failToolExecution(task.getTaskId(), failedTool.getExecutionId(), "exit code 1");
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);

        ToolExecutionState recoveredTool = recovered.getToolExecutions().get("tool-failure-main");
        boolean passed = recoveredTool != null
                && recoveredTool.getStatus() == ToolExecutionStatus.FAILED
                && "exit code 1".equals(recoveredTool.getErrorMessage())
                && recoveredTool.getCompletedAt() != null;
        return resultBuilder("tool-failure-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("tool-failure-main status=FAILED and error is recoverable")
                .actual("status=" + (recoveredTool == null ? "missing" : recoveredTool.getStatus())
                        + ", error=" + (recoveredTool == null ? "" : recoveredTool.getErrorMessage()))
                .details(Map.of(
                        "executionId", "tool-failure-main",
                        "toolName", "shell"))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult llmTimeoutRetryRecovery(String runId) {
        TaskState task = snapshotService.createTask(
                "runtime recovery benchmark llm timeout case " + runId,
                namespace(runId));
        String checkpointId = snapshotService.checkpoint(task.getTaskId());

        LlmCallState llmCall = snapshotService.startLlmCall(
                task.getTaskId(), "llm-timeout-main", "openai", "gpt-test", "produce recovery plan", 250L);
        snapshotService.timeoutLlmCall(task.getTaskId(), llmCall.getCallId(), "timeout after 250ms");
        snapshotService.markLlmCallRetry(task.getTaskId(), llmCall.getCallId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);

        LlmCallState recoveredCall = recovered.getLlmCalls().get("llm-timeout-main");
        boolean passed = recoveredCall != null
                && recoveredCall.getStatus() == LlmCallStatus.RETRY_PENDING
                && recoveredCall.isRetryable()
                && recoveredCall.getAttempt() == 2;
        return resultBuilder("llm-timeout-retry-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("llm-timeout-main status=RETRY_PENDING retryable=true attempt=2")
                .actual("status=" + (recoveredCall == null ? "missing" : recoveredCall.getStatus())
                        + ", retryable=" + (recoveredCall != null && recoveredCall.isRetryable())
                        + ", attempt=" + (recoveredCall == null ? 0 : recoveredCall.getAttempt()))
                .details(Map.of(
                        "callId", "llm-timeout-main",
                        "timeoutMillis", "250"))
                .build();
    }
    private RuntimeRecoveryBenchmarkReport.CaseResult walReplayAfterCheckpoint(String runId) {
        TaskState task = snapshotService.createTask("wal replay recovery " + runId, namespace(runId));
        DagNode base = snapshotService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        DagNode action = snapshotService.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "after checkpoint", base.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);
        snapshotService.completeNode(task.getTaskId(), action.getNodeId(), "action-done");
        snapshotService.updateContext(task.getTaskId(), "walReplay", "true");
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        boolean passed = recovered.getGraph().nodeCount() == 2
                && recovered.getGraph().edgeCount() == 1
                && "true".equals(recovered.getContext().get("walReplay"))
                && recovered.getGraph().getNode(action.getNodeId())
                .map(node -> node.getStatus() == DagNode.NodeStatus.COMPLETED)
                .orElse(false);
        return resultBuilder("wal-replay-after-restart", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("post-checkpoint node, edge, completion, and context are replayed")
                .actual("nodes=" + recovered.getGraph().nodeCount()
                        + ", edges=" + recovered.getGraph().edgeCount()
                        + ", walReplay=" + recovered.getContext().get("walReplay"))
                .details(details("baseNodeId", base.getNodeId(), "actionNodeId", action.getNodeId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult recoverThenContinue(String runId) {
        TaskState task = snapshotService.createTask("recover then continue " + runId, namespace(runId));
        DagNode root = snapshotService.appendNode(task.getTaskId(), "THOUGHT", "root");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        DagNode next = snapshotService.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "continue after recover", root.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);
        TaskState loaded = snapshotService.getTask(task.getTaskId()).orElseThrow();
        boolean passed = recovered.getStatus() == TaskState.TaskStatus.RUNNING
                && loaded.getGraph().nodeCount() == 2
                && loaded.getGraph().getNode(next.getNodeId()).isPresent();
        return resultBuilder("recover-then-continue", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("recovered running task accepts post-recovery DAG mutation")
                .actual("nodes=" + loaded.getGraph().nodeCount() + ", status=" + loaded.getStatus())
                .details(details("rootNodeId", root.getNodeId(), "nextNodeId", next.getNodeId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult latestCheckpointRecovery(String runId) {
        TaskState task = snapshotService.createTask("latest checkpoint recovery " + runId, namespace(runId));
        snapshotService.updateContext(task.getTaskId(), "version", "v1");
        String firstCheckpointId = snapshotService.checkpoint(task.getTaskId());
        snapshotService.updateContext(task.getTaskId(), "version", "v2");
        String latestCheckpointId = snapshotService.checkpoint(task.getTaskId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), null);
        boolean passed = latestCheckpointId.equals(recovered.getLatestCheckpointId())
                && "v2".equals(recovered.getContext().get("version"));
        return resultBuilder("latest-checkpoint-recover", task.getTaskId(), latestCheckpointId)
                .passed(passed)
                .expected("latest checkpoint resolves to v2 state")
                .actual("checkpoint=" + recovered.getLatestCheckpointId()
                        + ", version=" + recovered.getContext().get("version"))
                .details(details("firstCheckpointId", firstCheckpointId, "latestCheckpointId", latestCheckpointId))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult secondCrashAfterReplay(String runId) {
        RuntimeRecoveryBenchmarkReport.CaseResult replay = walReplayAfterCheckpoint(runId + "-double");
        if (!replay.isPassed()) {
            return replay;
        }
        RecoveryFingerprint first = fingerprint(snapshotService.recover(replay.getTaskId(), replay.getCheckpointId()));
        cacheEvictor.evict(snapshotService, replay.getTaskId());
        RecoveryFingerprint second = fingerprint(snapshotService.recover(replay.getTaskId(), replay.getCheckpointId()));
        boolean passed = first.equals(second);
        return resultBuilder("second-crash-after-replay", replay.getTaskId(), replay.getCheckpointId())
                .passed(passed)
                .expected("state after second crash/recover equals state after first replay")
                .actual("first=" + first + ", second=" + second)
                .details(replay.getDetails())
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult toolFailureVariantRecovery(
            String runId,
            String executionId,
            String toolName,
            String input,
            String errorMessage) {
        TaskState task = snapshotService.createTask("tool failure variant " + executionId + " " + runId, namespace(runId));
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        snapshotService.startToolExecution(task.getTaskId(), executionId, toolName, input);
        snapshotService.failToolExecution(task.getTaskId(), executionId, errorMessage);
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        ToolExecutionState recoveredTool = recovered.getToolExecutions().get(executionId);
        boolean passed = recoveredTool != null
                && recoveredTool.getStatus() == ToolExecutionStatus.FAILED
                && errorMessage.equals(recoveredTool.getErrorMessage())
                && recoveredTool.getCompletedAt() != null;
        return resultBuilder("tool-failure-variant-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected(executionId + " status=FAILED and error is recoverable")
                .actual("status=" + (recoveredTool == null ? "missing" : recoveredTool.getStatus())
                        + ", error=" + (recoveredTool == null ? "" : recoveredTool.getErrorMessage()))
                .details(details("executionId", executionId, "toolName", toolName, "input", input))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult runningToolRecovery(String runId) {
        TaskState task = snapshotService.createTask("running tool recovery " + runId, namespace(runId));
        ToolExecutionState runningTool = snapshotService.startToolExecution(
                task.getTaskId(), "tool-running-main", "browser", "wait for page");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        ToolExecutionState recoveredTool = recovered.getToolExecutions().get(runningTool.getExecutionId());
        boolean passed = recoveredTool != null
                && recoveredTool.getStatus() == ToolExecutionStatus.RUNNING
                && recoveredTool.getCompletedAt() == null;
        return resultBuilder("tool-running-resume", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("running tool remains RUNNING with no completion timestamp")
                .actual("status=" + (recoveredTool == null ? "missing" : recoveredTool.getStatus())
                        + ", completedAt=" + (recoveredTool == null ? "" : recoveredTool.getCompletedAt()))
                .details(details("executionId", runningTool.getExecutionId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult completedToolRecovery(String runId) {
        TaskState task = snapshotService.createTask("completed tool recovery " + runId, namespace(runId));
        ToolExecutionState tool = snapshotService.startToolExecution(task.getTaskId(), "tool-complete-main", "shell", "echo ok");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        snapshotService.completeToolExecution(task.getTaskId(), tool.getExecutionId(), "ok");
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        ToolExecutionState recoveredTool = recovered.getToolExecutions().get(tool.getExecutionId());
        boolean passed = recoveredTool != null
                && recoveredTool.getStatus() == ToolExecutionStatus.SUCCEEDED
                && "ok".equals(recoveredTool.getOutput());
        return resultBuilder("tool-completed-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("completed tool WAL replay restores SUCCEEDED output")
                .actual("status=" + (recoveredTool == null ? "missing" : recoveredTool.getStatus())
                        + ", output=" + (recoveredTool == null ? "" : recoveredTool.getOutput()))
                .details(details("executionId", tool.getExecutionId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult mixedToolStateRecovery(String runId) {
        TaskState task = snapshotService.createTask("mixed tool recovery " + runId, namespace(runId));
        snapshotService.startToolExecution(task.getTaskId(), "tool-done", "shell", "build");
        snapshotService.completeToolExecution(task.getTaskId(), "tool-done", "build ok");
        snapshotService.startToolExecution(task.getTaskId(), "tool-running", "http", "GET /status");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        boolean passed = recovered.getToolExecutions().size() == 2
                && recovered.getToolExecutions().get("tool-done").getStatus() == ToolExecutionStatus.SUCCEEDED
                && recovered.getToolExecutions().get("tool-running").getStatus() == ToolExecutionStatus.RUNNING;
        return resultBuilder("tool-mixed-state-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("one SUCCEEDED tool and one RUNNING tool recover together")
                .actual("toolCount=" + recovered.getToolExecutions().size())
                .details(details("completedExecutionId", "tool-done", "runningExecutionId", "tool-running"))
                .build();
    }
    private RuntimeRecoveryBenchmarkReport.CaseResult llmTimeoutVariantRecovery(String runId, String callId, String errorMessage) {
        TaskState task = snapshotService.createTask("llm timeout variant " + callId + " " + runId, namespace(runId));
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        snapshotService.startLlmCall(task.getTaskId(), callId, "openai", "gpt-test", "produce recovery plan", 250L);
        snapshotService.timeoutLlmCall(task.getTaskId(), callId, errorMessage);
        snapshotService.markLlmCallRetry(task.getTaskId(), callId);
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        LlmCallState recoveredCall = recovered.getLlmCalls().get(callId);
        boolean passed = recoveredCall != null
                && recoveredCall.getStatus() == LlmCallStatus.RETRY_PENDING
                && recoveredCall.isRetryable()
                && recoveredCall.getAttempt() == 2;
        return resultBuilder("llm-timeout-variant-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected(callId + " status=RETRY_PENDING retryable=true attempt=2")
                .actual("status=" + (recoveredCall == null ? "missing" : recoveredCall.getStatus())
                        + ", retryable=" + (recoveredCall != null && recoveredCall.isRetryable())
                        + ", attempt=" + (recoveredCall == null ? 0 : recoveredCall.getAttempt()))
                .details(details("callId", callId, "errorMessage", errorMessage))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult llmCompletedRecovery(String runId, String callId, String response) {
        TaskState task = snapshotService.createTask("llm completed recovery " + callId + " " + runId, namespace(runId));
        LlmCallState call = snapshotService.startLlmCall(task.getTaskId(), callId, "openai", "gpt-test", "answer", 500L);
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        snapshotService.completeLlmCall(task.getTaskId(), call.getCallId(), response);
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        LlmCallState recoveredCall = recovered.getLlmCalls().get(callId);
        boolean passed = recoveredCall != null
                && recoveredCall.getStatus() == LlmCallStatus.COMPLETED
                && response.equals(recoveredCall.getResponse())
                && !recoveredCall.isRetryable();
        return resultBuilder("llm-completed-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("LLM completed response recovers with retryable=false")
                .actual("status=" + (recoveredCall == null ? "missing" : recoveredCall.getStatus())
                        + ", response=" + (recoveredCall == null ? "" : recoveredCall.getResponse()))
                .details(details("callId", callId))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult runningLlmRecovery(String runId) {
        TaskState task = snapshotService.createTask("running llm recovery " + runId, namespace(runId));
        LlmCallState call = snapshotService.startLlmCall(
                task.getTaskId(), "llm-running-main", "openai", "gpt-test", "long reasoning", 5000L);
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        LlmCallState recoveredCall = recovered.getLlmCalls().get(call.getCallId());
        boolean passed = recoveredCall != null
                && recoveredCall.getStatus() == LlmCallStatus.RUNNING
                && recoveredCall.getCompletedAt() == null;
        return resultBuilder("llm-running-resume", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("running LLM call remains RUNNING with no completion timestamp")
                .actual("status=" + (recoveredCall == null ? "missing" : recoveredCall.getStatus())
                        + ", completedAt=" + (recoveredCall == null ? "" : recoveredCall.getCompletedAt()))
                .details(details("callId", call.getCallId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult llmTimeoutWalReplayRecovery(String runId) {
        TaskState task = snapshotService.createTask("llm timeout wal replay " + runId, namespace(runId));
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        snapshotService.startLlmCall(task.getTaskId(), "llm-wal-timeout", "openai", "gpt-test", "summarize", 250L);
        snapshotService.timeoutLlmCall(task.getTaskId(), "llm-wal-timeout", "timeout");
        snapshotService.markLlmCallRetry(task.getTaskId(), "llm-wal-timeout");
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        LlmCallState recoveredCall = recovered.getLlmCalls().get("llm-wal-timeout");
        boolean passed = recoveredCall != null && recoveredCall.getStatus() == LlmCallStatus.RETRY_PENDING;
        return resultBuilder("llm-timeout-wal-replay", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("LLM timeout and retry WAL entries replay")
                .actual("status=" + (recoveredCall == null ? "missing" : recoveredCall.getStatus()))
                .details(details("callId", "llm-wal-timeout"))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult memoryReferenceRecovery(String runId) {
        TaskState task = snapshotService.createTask("memory refs recovery " + runId, namespace(runId));
        task.getReferencedFragmentIds().add("fragment-alpha-" + runId);
        task.getReferencedFragmentIds().add("fragment-beta-" + runId);
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        boolean passed = recovered.getReferencedFragmentIds().containsAll(task.getReferencedFragmentIds())
                && recovered.getReferencedFragmentIds().size() == 2;
        return resultBuilder("memory-reference-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("2 referenced memory fragment IDs recover")
                .actual("referencedFragmentIds=" + recovered.getReferencedFragmentIds())
                .details(details("fragmentCount", String.valueOf(recovered.getReferencedFragmentIds().size())))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult contextRemovalRecovery(String runId) {
        TaskState task = snapshotService.createTask("context removal recovery " + runId, namespace(runId));
        snapshotService.updateContext(task.getTaskId(), "temporary", "present");
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        snapshotService.updateContext(task.getTaskId(), "temporary", null);
        snapshotService.updateContext(task.getTaskId(), "survivor", "kept");
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        boolean passed = !recovered.getContext().containsKey("temporary")
                && "kept".equals(recovered.getContext().get("survivor"));
        return resultBuilder("context-removal-wal-replay", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("null/empty context update removes temporary key and preserves survivor key")
                .actual("context=" + recovered.getContext())
                .details(details("removedKey", "temporary", "survivor", recovered.getContext().get("survivor")))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult dagDeleteNodeRecovery(String runId) {
        TaskState task = snapshotService.createTask("dag delete recovery " + runId, namespace(runId));
        DagNode first = snapshotService.appendNode(task.getTaskId(), "THOUGHT", "first");
        DagNode second = snapshotService.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "delete me", first.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        snapshotService.deleteNode(task.getTaskId(), second.getNodeId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        boolean passed = recovered.getGraph().nodeCount() == 1
                && recovered.getGraph().getNode(first.getNodeId()).isPresent()
                && recovered.getGraph().getNode(second.getNodeId()).isEmpty()
                && first.getNodeId().equals(recovered.getCurrentNodeId());
        return resultBuilder("dag-delete-node-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("deleted current node is absent and current node falls back to remaining sink")
                .actual("nodes=" + recovered.getGraph().nodeCount()
                        + ", currentNodeId=" + recovered.getCurrentNodeId())
                .details(details("firstNodeId", first.getNodeId(), "deletedNodeId", second.getNodeId()))
                .build();
    }
    private RuntimeRecoveryBenchmarkReport.CaseResult specialCharacterPayloadRecovery(String runId) {
        TaskState task = snapshotService.createTask("special payload recovery " + runId, namespace(runId));
        String content = "query=hello, filter=\"date\", path=C:\\tmp\\data";
        DagNode node = snapshotService.appendNode(task.getTaskId(), "THOUGHT", content);
        snapshotService.appendConversationMessage(task.getTaskId(), "special-conversation", "user", content);
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        boolean nodeContentRecovered = recovered.getGraph().getNode(node.getNodeId())
                .map(recoveredNode -> content.equals(recoveredNode.getContent()))
                .orElse(false);
        boolean conversationRecovered = recovered.getConversations().get("special-conversation").getMessages().stream()
                .anyMatch(message -> content.equals(message.getContent()));
        boolean passed = nodeContentRecovered && conversationRecovered;
        return resultBuilder("special-payload-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("JSON/WAL payload characters survive checkpoint and recovery")
                .actual("nodeContentRecovered=" + nodeContentRecovered
                        + ", conversationRecovered=" + conversationRecovered)
                .details(details("nodeId", node.getNodeId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult emptyRuntimeStateRecovery(String runId) {
        TaskState task = snapshotService.createTask("empty state recovery " + runId, namespace(runId));
        String checkpointId = snapshotService.checkpoint(task.getTaskId());
        cacheEvictor.evict(snapshotService, task.getTaskId());
        TaskState recovered = snapshotService.recover(task.getTaskId(), checkpointId);
        boolean passed = recovered.getConversations() != null
                && recovered.getToolExecutions() != null
                && recovered.getLlmCalls() != null
                && recovered.getReferencedFragmentIds() != null
                && recovered.getConversations().isEmpty()
                && recovered.getToolExecutions().isEmpty()
                && recovered.getLlmCalls().isEmpty()
                && recovered.getReferencedFragmentIds().isEmpty();
        return resultBuilder("empty-runtime-state-recover", task.getTaskId(), checkpointId)
                .passed(passed)
                .expected("optional runtime-state containers are initialized and empty")
                .actual("conversations=" + sizeOf(recovered.getConversations())
                        + ", tools=" + sizeOf(recovered.getToolExecutions())
                        + ", llmCalls=" + sizeOf(recovered.getLlmCalls())
                        + ", refs=" + sizeOf(recovered.getReferencedFragmentIds()))
                .details(details("taskId", task.getTaskId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult multiTaskRecovery(String runId) {
        List<TaskState> tasks = new ArrayList<>();
        List<String> checkpointIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TaskState task = snapshotService.createTask("multi task recovery " + i + " " + runId, namespace(runId));
            snapshotService.updateContext(task.getTaskId(), "index", String.valueOf(i));
            snapshotService.appendNode(task.getTaskId(), "THOUGHT", "task " + i);
            checkpointIds.add(snapshotService.checkpoint(task.getTaskId()));
            tasks.add(task);
        }
        tasks.forEach(task -> cacheEvictor.evict(snapshotService, task.getTaskId()));
        List<TaskState> recovered = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            recovered.add(snapshotService.recover(tasks.get(i).getTaskId(), checkpointIds.get(i)));
        }
        boolean passed = recovered.size() == 3
                && recovered.stream().allMatch(task -> task.getGraph().nodeCount() == 1)
                && recovered.stream().map(task -> task.getContext().get("index")).distinct().count() == 3;
        return resultBuilder("multi-task-recover", tasks.getFirst().getTaskId(), checkpointIds.getFirst())
                .passed(passed)
                .expected("3 independent tasks recover their own DAG/context state")
                .actual("recoveredTasks=" + recovered.size()
                        + ", indexes=" + recovered.stream().map(task -> task.getContext().get("index")).toList())
                .details(details("taskCount", String.valueOf(tasks.size())))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult interleavedNewRequestRecovery(String runId) {
        TaskState recoveringTask = snapshotService.createTask("recovering task " + runId, namespace(runId));
        snapshotService.appendNode(recoveringTask.getTaskId(), "THOUGHT", "before recovery");
        String checkpointId = snapshotService.checkpoint(recoveringTask.getTaskId());
        TaskState newRequestTask = snapshotService.createTask("new request during recovery " + runId, namespace(runId));
        cacheEvictor.evict(snapshotService, recoveringTask.getTaskId());
        TaskState recovered = snapshotService.recover(recoveringTask.getTaskId(), checkpointId);
        DagNode newNode = snapshotService.appendNode(newRequestTask.getTaskId(), "ACTION", "new request accepted");
        TaskState loadedNewTask = snapshotService.getTask(newRequestTask.getTaskId()).orElseThrow();
        boolean passed = recovered.getGraph().nodeCount() == 1
                && loadedNewTask.getGraph().getNode(newNode.getNodeId()).isPresent();
        return resultBuilder("interleaved-new-request-recover", recoveringTask.getTaskId(), checkpointId)
                .passed(passed)
                .expected("recovery of one task does not corrupt a new task mutation")
                .actual("recoveredNodes=" + recovered.getGraph().nodeCount()
                        + ", newTaskNodes=" + loadedNewTask.getGraph().nodeCount())
                .details(details("newTaskId", newRequestTask.getTaskId(), "newNodeId", newNode.getNodeId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult multiTaskRepeatedRecovery(String runId) {
        TaskState first = snapshotService.createTask("repeat first " + runId, namespace(runId));
        TaskState second = snapshotService.createTask("repeat second " + runId, namespace(runId));
        snapshotService.appendNode(first.getTaskId(), "THOUGHT", "first");
        snapshotService.appendNode(second.getTaskId(), "THOUGHT", "second");
        String firstCheckpoint = snapshotService.checkpoint(first.getTaskId());
        String secondCheckpoint = snapshotService.checkpoint(second.getTaskId());
        cacheEvictor.evict(snapshotService, first.getTaskId());
        cacheEvictor.evict(snapshotService, second.getTaskId());
        RecoveryFingerprint firstOnce = fingerprint(snapshotService.recover(first.getTaskId(), firstCheckpoint));
        RecoveryFingerprint secondOnce = fingerprint(snapshotService.recover(second.getTaskId(), secondCheckpoint));
        cacheEvictor.evict(snapshotService, first.getTaskId());
        cacheEvictor.evict(snapshotService, second.getTaskId());
        RecoveryFingerprint firstTwice = fingerprint(snapshotService.recover(first.getTaskId(), firstCheckpoint));
        RecoveryFingerprint secondTwice = fingerprint(snapshotService.recover(second.getTaskId(), secondCheckpoint));
        boolean passed = firstOnce.equals(firstTwice) && secondOnce.equals(secondTwice);
        return resultBuilder("multi-task-repeated-recover", first.getTaskId(), firstCheckpoint)
                .passed(passed)
                .expected("repeated recovery remains idempotent for two independent tasks")
                .actual("firstEqual=" + firstOnce.equals(firstTwice)
                        + ", secondEqual=" + secondOnce.equals(secondTwice))
                .details(details("secondTaskId", second.getTaskId(), "secondCheckpointId", secondCheckpoint))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult executionIdCrossTaskIsolation(String runId) {
        RuntimeRecoveryBenchmarkReport.CaseResult first = executionIdReplayIdempotency(runId + "-task-a");
        RuntimeRecoveryBenchmarkReport.CaseResult second = executionIdReplayIdempotency(runId + "-task-b");
        boolean passed = first.isPassed()
                && second.isPassed()
                && !first.getTaskId().equals(second.getTaskId());
        return resultBuilder("execution-id-cross-task-isolation", first.getTaskId(), null)
                .passed(passed)
                .expected("distinct execution IDs isolate replay caches across two tasks")
                .actual("firstPassed=" + first.isPassed()
                        + ", secondPassed=" + second.isPassed()
                        + ", taskIdsDistinct=" + !first.getTaskId().equals(second.getTaskId()))
                .details(details("secondTaskId", second.getTaskId()))
                .build();
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult multiTaskConversationRecovery(String runId) {
        TaskState first = snapshotService.createTask("conversation first " + runId, namespace(runId));
        TaskState second = snapshotService.createTask("conversation second " + runId, namespace(runId));
        snapshotService.appendConversationMessage(first.getTaskId(), "conversation-a", "user", "first message");
        snapshotService.appendConversationMessage(second.getTaskId(), "conversation-b", "user", "second message");
        String firstCheckpoint = snapshotService.checkpoint(first.getTaskId());
        String secondCheckpoint = snapshotService.checkpoint(second.getTaskId());
        cacheEvictor.evict(snapshotService, first.getTaskId());
        cacheEvictor.evict(snapshotService, second.getTaskId());
        TaskState recoveredFirst = snapshotService.recover(first.getTaskId(), firstCheckpoint);
        TaskState recoveredSecond = snapshotService.recover(second.getTaskId(), secondCheckpoint);
        boolean passed = recoveredFirst.getConversations().containsKey("conversation-a")
                && recoveredSecond.getConversations().containsKey("conversation-b")
                && !recoveredFirst.getConversations().containsKey("conversation-b")
                && !recoveredSecond.getConversations().containsKey("conversation-a");
        return resultBuilder("multi-task-conversation-recover", first.getTaskId(), firstCheckpoint)
                .passed(passed)
                .expected("conversation state does not bleed across recovered tasks")
                .actual("firstConversations=" + recoveredFirst.getConversations().keySet()
                        + ", secondConversations=" + recoveredSecond.getConversations().keySet())
                .details(details("secondTaskId", second.getTaskId(), "secondCheckpointId", secondCheckpoint))
                .build();
    }
    private ResponseEntity<Map<String, Object>> executeAppendWithExecutionId(
            String taskId,
            String executionId,
            Map<String, Object> request) {
        Supplier<ResponseEntity<Map<String, Object>>> action = () -> {
            DagNode node = snapshotService.appendNode(taskId, "THOUGHT", "execution-id guarded append");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("nodeId", node.getNodeId());
            body.put("nodeCount", snapshotService.getTask(taskId).orElseThrow().getGraph().nodeCount());
            return ResponseEntity.ok(body);
        };
        return executionIdService.execute(executionId, "runtime.benchmark.append-node", request, action);
    }

    private RuntimeRecoveryBenchmarkReport.CaseResult runCase(BenchmarkCase benchmarkCase) {
        long startedAt = System.nanoTime();
        try {
            RuntimeRecoveryBenchmarkReport.CaseResult result = benchmarkCase.action().get();
            result.setCaseId(benchmarkCase.caseId());
            result.setName(benchmarkCase.name());
            result.setCategory(benchmarkCase.category());
            result.setCapability(benchmarkCase.capability());
            result.setLatencyMs(elapsedMillis(startedAt));
            log.info("Runtime recovery benchmark case completed caseId={} category={} passed={}",
                    benchmarkCase.caseId(), benchmarkCase.category(), result.isPassed());
            return result;
        } catch (RuntimeException e) {
            log.warn("Runtime recovery benchmark case failed caseId={} name={}",
                    benchmarkCase.caseId(), benchmarkCase.name(), e);
            return RuntimeRecoveryBenchmarkReport.CaseResult.builder()
                    .caseId(benchmarkCase.caseId())
                    .name(benchmarkCase.name())
                    .category(benchmarkCase.category())
                    .capability(benchmarkCase.capability())
                    .passed(false)
                    .latencyMs(elapsedMillis(startedAt))
                    .expected("case completes without exception")
                    .actual("exception")
                    .errorMessage(e.getClass().getSimpleName() + ": " + nullToEmpty(e.getMessage()))
                    .build();
        }
    }
    private RuntimeRecoveryBenchmarkReport.CaseResult.CaseResultBuilder resultBuilder(
            String capability,
            String taskId,
            String checkpointId) {
        return RuntimeRecoveryBenchmarkReport.CaseResult.builder()
                .capability(capability)
                .taskId(taskId)
                .checkpointId(checkpointId);
    }

    private List<RuntimeRecoveryBenchmarkReport.CategorySummary> categorySummaries(
            List<RuntimeRecoveryBenchmarkReport.CaseResult> results) {
        Map<String, List<RuntimeRecoveryBenchmarkReport.CaseResult>> byCategory = new LinkedHashMap<>();
        for (RuntimeRecoveryBenchmarkReport.CaseResult result : results) {
            byCategory.computeIfAbsent(result.getCategory(), ignored -> new ArrayList<>()).add(result);
        }
        return byCategory.entrySet().stream()
                .map(entry -> {
                    int total = entry.getValue().size();
                    int passed = (int) entry.getValue().stream()
                            .filter(RuntimeRecoveryBenchmarkReport.CaseResult::isPassed)
                            .count();
                    long latency = entry.getValue().stream()
                            .mapToLong(RuntimeRecoveryBenchmarkReport.CaseResult::getLatencyMs)
                            .sum();
                    return RuntimeRecoveryBenchmarkReport.CategorySummary.builder()
                            .category(entry.getKey())
                            .totalCases(total)
                            .passedCases(passed)
                            .failedCases(total - passed)
                            .successRate(total == 0 ? 0.0d : (double) passed / total)
                            .totalLatencyMs(latency)
                            .averageLatencyMs(total == 0 ? 0.0d : (double) latency / total)
                            .build();
                })
                .toList();
    }
    private RecoveryFingerprint fingerprint(TaskState state) {
        List<String> nodeIds = state.getGraph().getNodes().keySet().stream().sorted().toList();
        List<String> edges = state.getGraph().edgeSnapshot().stream()
                .map(edge -> edge.getSourceNodeId() + ">" + edge.getTargetNodeId() + ":" + edge.getDependencyType())
                .sorted()
                .toList();
        Map<String, String> context = new LinkedHashMap<>();
        state.getContext().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> context.put(entry.getKey(), entry.getValue()));
        return new RecoveryFingerprint(
                state.getGraph().nodeCount(),
                state.getGraph().edgeCount(),
                state.getCurrentNodeId(),
                state.getCurrentBranchId(),
                state.getWalSequenceNumber(),
                nodeIds,
                edges,
                context);
    }

    private Map<String, String> details(String... keyValues) {
        Map<String, String> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            details.put(nullToEmpty(keyValues[i]), nullToEmpty(keyValues[i + 1]));
        }
        return details;
    }

    private String namespace(String runId) {
        return "runtime-recovery-benchmark-" + runId;
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int sizeOf(Map<?, ?> value) {
        return value == null ? -1 : value.size();
    }

    private int sizeOf(List<?> value) {
        return value == null ? -1 : value.size();
    }

    private record BenchmarkCase(
            String caseId,
            String name,
            String category,
            String capability,
            Supplier<RuntimeRecoveryBenchmarkReport.CaseResult> action) {
    }

    private record RecoveryFingerprint(
            int nodeCount,
            int edgeCount,
            String currentNodeId,
            String currentBranchId,
            long walSequenceNumber,
            List<String> nodeIds,
            List<String> edges,
            Map<String, String> context) {
    }
}

