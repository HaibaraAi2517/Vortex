package com.vortex.kernel.snapshot;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.model.*;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.storage.api.L3ColdStore;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Full lifecycle tests for SnapshotService using real WAL and a fake L3 store.
 */
class SnapshotServiceTest {

    @TempDir
    Path tempDir;

    private SnapshotService service;
    private ActionLogWriter walWriter;
    private ActionLogReader walReader;
    private ActionLogTruncator walTruncator;
    private IncrementalCheckpointManager checkpointManager;
    private CheckpointScheduler scheduler;
    private DirtySetTracker dirtySetTracker;
    private DotGraphExporter dotExporter;
    private FakeL3ColdStore fakeL3;
    private BranchManager branchManager;
    private BranchMergeConflictDetector conflictDetector;
    private CheckpointRecoveryMetrics checkpointRecoveryMetrics;
    private MemorySloTracker memorySloTracker;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        fakeL3 = new FakeL3ColdStore();
        service = newService(fakeL3);
    }

    @Test
    void createTask_generatesId_and_statusRunning() {
        TaskState task = service.createTask("test task", "ns-1");
        assertThat(task.getTaskId()).isNotNull();
        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.RUNNING);
        assertThat(task.getGraph()).isNotNull();
        assertThat(task.getGraph().nodeCount()).isZero();
    }

    @Test
    void deltaCheckpointPreservesBranchForkNodeAndEdge() {
        TaskState task = service.createTask("branch delta", "ns");
        DagNode root = service.appendNode(task.getTaskId(), "THOUGHT", "root");
        service.checkpoint(task.getTaskId());
        TaskBranch branch = service.createBranch(task.getTaskId(), "alternative", root.getNodeId());

        TaskState recovered = service.recover(task.getTaskId(), service.checkpoint(task.getTaskId()));

        assertThat(recovered.getGraph().getNode(branch.getForkNodeId())).isPresent();
        assertThat(recovered.getGraph().areConnected(root.getNodeId(), branch.getForkNodeId())).isTrue();
        assertThat(recovered.getCurrentNodeId()).isEqualTo(branch.getForkNodeId());
    }

    @Test
    void deltaCheckpointPreservesMergeNode() {
        TaskState task = service.createTask("merge delta", "ns");
        DagNode root = service.appendNode(task.getTaskId(), "THOUGHT", "root");
        TaskBranch source = service.createBranch(task.getTaskId(), "source", root.getNodeId());
        TaskBranch target = service.createBranch(task.getTaskId(), "target", root.getNodeId());
        service.checkpoint(task.getTaskId());
        service.mergeBranch(task.getTaskId(), source.getBranchId(), target.getBranchId());
        String mergeId = task.getCurrentNodeId();

        TaskState recovered = service.recover(task.getTaskId(), service.checkpoint(task.getTaskId()));

        assertThat(recovered.getGraph().getNode(mergeId)).isPresent();
        assertThat(recovered.getCurrentNodeId()).isEqualTo(mergeId);
    }

    @Test
    void mergeWalReplayPreservesNodeIdentityForSubsequentEdges() {
        TaskState task = service.createTask("merge replay", "ns");
        DagNode root = service.appendNode(task.getTaskId(), "THOUGHT", "root");
        TaskBranch source = service.createBranch(task.getTaskId(), "source", root.getNodeId());
        TaskBranch target = service.createBranch(task.getTaskId(), "target", root.getNodeId());
        String checkpoint = service.checkpoint(task.getTaskId());
        service.mergeBranch(task.getTaskId(), source.getBranchId(), target.getBranchId());
        String mergeId = task.getCurrentNodeId();
        DagNode next = service.appendNodeWithTarget(task.getTaskId(), "THOUGHT", "next",
                mergeId, DagEdge.EdgeType.CONTROL_DEP);

        TaskState recovered = service.recover(task.getTaskId(), checkpoint);

        assertThat(recovered.getGraph().getNode(mergeId)).isPresent();
        assertThat(recovered.getGraph().areConnected(mergeId, next.getNodeId())).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"dag", "runtime", "branch"})
    void checkpointWaitsUntilLoggedMutationIsApplied(String mutationKind) throws Exception {
        TaskState task = service.createTask("checkpoint race", "ns");
        DagNode root = service.appendNode(task.getTaskId(), "THOUGHT", "root");
        service.checkpoint(task.getTaskId());
        TaskState other = service.createTask("independent task", "ns");
        CountDownLatch logged = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ActionLogWriter original = walWriter;
        ActionLogWriter paused = org.mockito.Mockito.mock(ActionLogWriter.class);
        org.mockito.Mockito.when(paused.append(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(ActionLogEntry.OperationType.class),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    ActionLogEntry entry = original.append(invocation.getArgument(0),
                            invocation.getArgument(1), invocation.getArgument(2));
                    logged.countDown();
                    assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
                    return entry;
                });
        Object mutationOwner = switch (mutationKind) {
            case "dag" -> org.springframework.test.util.ReflectionTestUtils.getField(service, "dagMutationService");
            case "runtime" -> org.springframework.test.util.ReflectionTestUtils.getField(service, "runtimeMutationService");
            default -> service;
        };
        org.springframework.test.util.ReflectionTestUtils.setField(mutationOwner, "walWriter", paused);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var mutation = executor.submit(() -> {
                switch (mutationKind) {
                    case "dag" -> service.appendNode(task.getTaskId(), "THOUGHT", "concurrent");
                    case "runtime" -> service.appendConversationMessage(task.getTaskId(), "chat", "user", "concurrent");
                    default -> service.createBranch(task.getTaskId(), "concurrent", root.getNodeId());
                }
            });
            try {
                assertThat(logged.await(5, TimeUnit.SECONDS)).isTrue();
                var checkpoint = executor.submit(() -> service.checkpoint(task.getTaskId()));
                assertThatThrownBy(() -> checkpoint.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(executor.submit(() -> service.checkpoint(other.getTaskId()))
                        .get(2, TimeUnit.SECONDS)).isNotBlank();
                resume.countDown();
                mutation.get(5, TimeUnit.SECONDS);
                TaskState recovered = service.recover(task.getTaskId(), checkpoint.get(5, TimeUnit.SECONDS));
                if (mutationKind.equals("runtime")) {
                    assertThat(recovered.getConversations().get("chat").getMessages()).hasSize(1);
                } else {
                    assertThat(recovered.getGraph().nodeCount()).isEqualTo(2);
                    assertThat(recovered.getGraph().getNode(task.getCurrentNodeId())).isPresent();
                }
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void appendNode_addsToGraph() {
        TaskState task = service.createTask("test", "ns");
        DagNode node = service.appendNode(task.getTaskId(), "THOUGHT", "step 1");
        assertThat(task.getGraph().nodeCount()).isEqualTo(1);
        assertThat(node.getType()).isEqualTo(DagNode.NodeType.THOUGHT);
        assertThat(node.getContent()).isEqualTo("step 1");
        assertThat(task.getCurrentNodeId()).isEqualTo(node.getNodeId());
    }

    @Test
    void appendNodeWithTarget_createsEdge() {
        TaskState task = service.createTask("test", "ns");
        DagNode n1 = service.appendNode(task.getTaskId(), "THOUGHT", "start");
        DagNode n2 = service.appendNodeWithTarget(task.getTaskId(), "ACTION", "execute",
                n1.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);

        assertThat(task.getGraph().getEdges()).hasSize(1);
        assertThat(task.getGraph().areConnected(n1.getNodeId(), n2.getNodeId())).isTrue();
    }

    @Test
    void completeNode_setsResult() {
        TaskState task = service.createTask("test", "ns");
        DagNode node = service.appendNode(task.getTaskId(), "ACTION", "run");
        DagNode completed = service.completeNode(task.getTaskId(), node.getNodeId(), "success");

        assertThat(completed.getStatus()).isEqualTo(DagNode.NodeStatus.COMPLETED);
        assertThat(completed.getResult()).isEqualTo("success");
    }

    @Test
    void appendNode_invalidType_doesNotWriteWal() {
        TaskState task = service.createTask("invalid node type", "ns");
        long seqBefore = walWriter.currentSequenceNumber(task.getTaskId());
        int walEntriesBefore = walReader.readAll(task.getTaskId()).size();

        assertThatThrownBy(() -> service.appendNode(task.getTaskId(), "NOT_A_REAL_TYPE", "bad node"))
                .isInstanceOf(IllegalArgumentException.class);

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
        assertThat(task.getGraph().nodeCount()).isZero();
    }

    @Test
    void appendNodeWithTarget_missingTarget_doesNotWriteWal() {
        TaskState task = service.createTask("missing target", "ns");
        long seqBefore = walWriter.currentSequenceNumber(task.getTaskId());
        int walEntriesBefore = walReader.readAll(task.getTaskId()).size();

        assertThatThrownBy(() -> service.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "bad edge", "missing-node", DagEdge.EdgeType.CONTROL_DEP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Node not found");

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
        assertThat(task.getGraph().nodeCount()).isZero();
        assertThat(task.getGraph().getEdges()).isEmpty();
    }

    @Test
    void completeNode_missingNode_doesNotWriteWal() {
        TaskState task = service.createTask("missing complete target", "ns");
        long seqBefore = walWriter.currentSequenceNumber(task.getTaskId());
        int walEntriesBefore = walReader.readAll(task.getTaskId()).size();

        assertThatThrownBy(() -> service.completeNode(task.getTaskId(), "missing-node", "result"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Node not found");

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
    }

    @Test
    void addEdge_invalidSource_doesNotWriteWalOrPoisonRecovery() {
        TaskState task = service.createTask("invalid edge", "ns");
        DagNode node = service.appendNode(task.getTaskId(), "THOUGHT", "existing");
        String checkpointId = service.checkpoint(task.getTaskId());
        long seqBefore = walWriter.currentSequenceNumber(task.getTaskId());
        int walEntriesBefore = walReader.readAll(task.getTaskId()).size();

        assertThatThrownBy(() -> service.addEdge(
                task.getTaskId(), "missing-source", node.getNodeId(), DagEdge.EdgeType.CONTROL_DEP, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source node not found");

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
        service.evictFromCacheForTest(task.getTaskId());

        TaskState recovered = service.recover(task.getTaskId(), checkpointId);
        assertThat(recovered.getGraph().nodeCount()).isEqualTo(1);
        assertThat(recovered.getGraph().getEdges()).isEmpty();
    }

    @Test
    void checkpoint_and_recover_restoresExactState() {
        // Create task and populate
        TaskState task = service.createTask("recovery test", "ns");
        DagNode n1 = service.appendNode(task.getTaskId(), "THOUGHT", "plan");
        service.completeNode(task.getTaskId(), n1.getNodeId(), "plan done");
        DagNode n2 = service.appendNodeWithTarget(task.getTaskId(), "ACTION", "execute",
                n1.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);
        service.updateContext(task.getTaskId(), "key1", "val1");

        // Checkpoint
        String cpId = service.checkpoint(task.getTaskId());
        assertThat(cpId).isNotNull();

        // Simulate crash by clearing active tasks
        String taskId = task.getTaskId();
        // Register the task in active (it was lost on "crash")
        // We need to clear it and recover
        // Actually, the task IS in activeTasks. Let's simulate a full recovery:
        TaskState recovered = service.recover(taskId, cpId);

        assertThat(recovered.getGraph().nodeCount()).isEqualTo(2);
        assertThat(recovered.getContext()).containsEntry("key1", "val1");
        assertThat(recovered.getGraph().getEdges()).hasSize(1);
        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.RUNNING);
    }

    @Test
    void recover_withWalReplay_replaysNewEntries() {
        TaskState task = service.createTask("wal-replay test", "ns");
        DagNode n1 = service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        service.completeNode(task.getTaskId(), n1.getNodeId(), "done");

        String cpId = service.checkpoint(task.getTaskId());

        // Append more nodes AFTER checkpoint
        DagNode n2 = service.appendNode(task.getTaskId(), "ACTION", "after checkpoint");
        service.completeNode(task.getTaskId(), n2.getNodeId(), "also done");

        // recover
        String taskId = task.getTaskId();
        TaskState recovered = service.recover(taskId, cpId);

        // Should have both nodes (from checkpoint + WAL replay)
        assertThat(recovered.getGraph().nodeCount()).isEqualTo(2);
        assertThat(recovered.getGraph().getNode(n1.getNodeId())).isPresent();
        assertThat(recovered.getGraph().getNode(n2.getNodeId())).isPresent();
        assertThat(recovered.getGraph().getNode(n2.getNodeId()).get().getStatus())
                .isEqualTo(DagNode.NodeStatus.COMPLETED);
    }

    @Test
    void recover_withWalReplay_replaysRuntimeStateEntries() {
        TaskState task = service.createTask("runtime wal replay", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());

        service.appendConversationMessage(task.getTaskId(), "conversation-1", "user", "retry deployment");
        service.startToolExecution(task.getTaskId(), "tool-1", "shell", "deploy --dry-run");
        service.failToolExecution(task.getTaskId(), "tool-1", "exit code 1");
        service.startLlmCall(task.getTaskId(), "llm-1", "openai", "gpt-test", "summarize failure", 250L);
        service.timeoutLlmCall(task.getTaskId(), "llm-1", "timeout after 250ms");
        service.markLlmCallRetry(task.getTaskId(), "llm-1");

        service.evictFromCacheForTest(task.getTaskId());
        TaskState recovered = service.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getConversations()).containsKey("conversation-1");
        assertThat(recovered.getConversations().get("conversation-1").getMessages())
                .extracting(ConversationMessage::getContent)
                .containsExactly("retry deployment");
        assertThat(recovered.getToolExecutions()).containsKey("tool-1");
        assertThat(recovered.getToolExecutions().get("tool-1").getStatus())
                .isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(recovered.getToolExecutions().get("tool-1").getErrorMessage())
                .isEqualTo("exit code 1");
        assertThat(recovered.getLlmCalls()).containsKey("llm-1");
        assertThat(recovered.getLlmCalls().get("llm-1").getStatus())
                .isEqualTo(LlmCallStatus.RETRY_PENDING);
        assertThat(recovered.getLlmCalls().get("llm-1").isRetryable()).isTrue();
        assertThat(recovered.getLlmCalls().get("llm-1").getAttempt()).isEqualTo(2);
    }
    @Test
    void recover_sameCheckpointTwice_replaysWalEachTime() {
        TaskState task = service.createTask("repeat-recovery test", "ns");
        DagNode beforeCheckpoint = service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());

        DagNode afterCheckpoint = service.appendNode(task.getTaskId(), "ACTION", "after checkpoint");
        service.completeNode(task.getTaskId(), afterCheckpoint.getNodeId(), "done");

        TaskState firstRecovery = service.recover(task.getTaskId(), checkpointId);
        assertThat(firstRecovery.getGraph().getNode(beforeCheckpoint.getNodeId())).isPresent();
        assertThat(firstRecovery.getGraph().getNode(afterCheckpoint.getNodeId())).isPresent();

        service.evictFromCacheForTest(task.getTaskId());

        TaskState secondRecovery = service.recover(task.getTaskId(), checkpointId);
        assertThat(secondRecovery.getGraph().getNode(beforeCheckpoint.getNodeId())).isPresent();
        assertThat(secondRecovery.getGraph().getNode(afterCheckpoint.getNodeId())).isPresent();
        assertThat(secondRecovery.getGraph().getNode(afterCheckpoint.getNodeId()).orElseThrow().getStatus())
                .isEqualTo(DagNode.NodeStatus.COMPLETED);
    }

    @Test
    void walReplay_preservesContentWithCommasAndSpecialChars() {
        TaskState task = service.createTask("comma test", "ns");
        String contentWithComma = "search tool: query=hello, filter=date, limit=10";
        String contentWithQuote = "result: \"found 3 items\" and path C:\\temp\\data";

        service.appendNode(task.getTaskId(), "THOUGHT", contentWithComma);
        service.appendNode(task.getTaskId(), "ACTION", contentWithQuote);
        service.checkpoint(task.getTaskId());

        service.evictFromCacheForTest(task.getTaskId());

        Optional<TaskState> recovered = service.getTask(task.getTaskId());

        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().getGraph().getNodes().values())
                .extracting(DagNode::getContent)
                .contains(contentWithComma, contentWithQuote);
    }

    @Test
    void recover_withoutCheckpointId_afterRestart_usesLatestCheckpointFromL3() {
        TaskState task = service.createTask("restart recovery test", "ns");
        DagNode node = service.appendNode(task.getTaskId(), "THOUGHT", "persist me");
        service.completeNode(task.getTaskId(), node.getNodeId(), "done");
        service.updateContext(task.getTaskId(), "resume", "true");

        String checkpointId = service.checkpoint(task.getTaskId());

        SnapshotService restarted = newService(fakeL3);
        TaskState recovered = restarted.recover(task.getTaskId(), null);

        assertThat(recovered.getLatestCheckpointId()).isEqualTo(checkpointId);
        assertThat(recovered.getGraph().getNode(node.getNodeId())).isPresent();
        assertThat(recovered.getContext()).containsEntry("resume", "true");
        assertThat(restarted.listCheckpoints(task.getTaskId()))
                .extracting(CheckpointMetadata::getCheckpointId)
                .contains(checkpointId);
    }

    @Test
    void recover_afterRestart_keepsWalSequenceMonotonicForNewMutations() {
        TaskState task = service.createTask("wal monotonic after restart", "ns");
        DagNode baseNode = service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());

        service = newService(fakeL3);
        TaskState recoveredOnce = service.recover(task.getTaskId(), checkpointId);
        assertThat(recoveredOnce.getGraph().getNode(baseNode.getNodeId())).isPresent();

        DagNode postRestartNode = service.appendNode(task.getTaskId(), "ACTION", "after restart before next checkpoint");

        service = newService(fakeL3);
        TaskState recoveredTwice = service.recover(task.getTaskId(), checkpointId);

        assertThat(recoveredTwice.getGraph().getNode(baseNode.getNodeId())).isPresent();
        assertThat(recoveredTwice.getGraph().getNode(postRestartNode.getNodeId())).isPresent();
    }

    @Test
    void recover_rejoinsAutomaticCheckpointing() {
        service = newService(fakeL3, 10, 20, 7, true, 1, 60000);

        TaskState task = service.createTask("recovery scheduler test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());
        int checkpointsBefore = service.listCheckpoints(task.getTaskId()).size();

        service.evictFromCacheForTest(task.getTaskId());
        TaskState recovered = service.recover(task.getTaskId(), checkpointId);
        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.RUNNING);

        DagNode postRecoverNode = service.appendNode(task.getTaskId(), "ACTION", "after recover");
        scheduler.scheduledScan();

        assertThat(service.listCheckpoints(task.getTaskId()).size()).isEqualTo(checkpointsBefore + 1);
        assertThat(service.getTask(task.getTaskId()).orElseThrow().getGraph().getNode(postRecoverNode.getNodeId()))
                .isPresent();
    }

    @Test
    void recover_timeTriggerInheritsCheckpointBaselineForExplicitAndLazyRecovery() throws Exception {
        service = newService(fakeL3, 10, 20, 7, true, 100, 25);

        TaskState explicitTask = service.createTask("explicit time baseline", "ns");
        service.appendNode(explicitTask.getTaskId(), "THOUGHT", "before explicit recover");
        String explicitCheckpointId = service.checkpoint(explicitTask.getTaskId());
        int explicitCheckpointsBefore = service.listCheckpoints(explicitTask.getTaskId()).size();

        Thread.sleep(50L);
        service.evictFromCacheForTest(explicitTask.getTaskId());
        service.recover(explicitTask.getTaskId(), explicitCheckpointId);
        scheduler.scheduledScan();

        assertThat(service.listCheckpoints(explicitTask.getTaskId()).size())
                .isEqualTo(explicitCheckpointsBefore + 1);

        TaskState lazyTask = service.createTask("lazy time baseline", "ns");
        service.appendNode(lazyTask.getTaskId(), "THOUGHT", "before lazy recover");
        service.checkpoint(lazyTask.getTaskId());
        int lazyCheckpointsBefore = service.listCheckpoints(lazyTask.getTaskId()).size();

        Thread.sleep(50L);
        service.evictFromCacheForTest(lazyTask.getTaskId());
        assertThat(service.getTask(lazyTask.getTaskId())).isPresent();
        scheduler.scheduledScan();

        assertThat(service.listCheckpoints(lazyTask.getTaskId()).size())
                .isEqualTo(lazyCheckpointsBefore + 1);
    }

    @Test
    void recover_terminalTaskDoesNotRejoinScheduler() throws Exception {
        service = newService(fakeL3, 10, 20, 7, true, 1, 25);

        TaskState task = service.createTask("terminal recovery scheduler guard", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "terminal");
        service.completeTask(task.getTaskId());
        int checkpointsBefore = service.listCheckpoints(task.getTaskId()).size();

        service = newService(fakeL3, 10, 20, 7, true, 1, 25);
        TaskState recovered = service.recover(task.getTaskId(), null);
        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(recovered.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);

        Thread.sleep(50L);
        scheduler.scheduledScan();

        assertThat(service.listCheckpoints(task.getTaskId()).size()).isEqualTo(checkpointsBefore);
        assertThat(isTaskRegisteredWithScheduler(task.getTaskId())).isFalse();
    }

    @Test
    void recoverReplacingLoadedRunningTask_doesNotTriggerEmergencyCheckpoint() {
        service = newService(fakeL3);

        TaskState task = service.createTask("replace recovered task", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());
        int checkpointsBefore = service.listCheckpoints(task.getTaskId()).size();

        service.appendNode(task.getTaskId(), "ACTION", "after checkpoint before recover");
        TaskState recovered = service.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getGraph().nodeCount()).isEqualTo(2);
        assertThat(service.listCheckpoints(task.getTaskId()).size()).isEqualTo(checkpointsBefore);
    }

    @Test
    void recover_fromDeltaCheckpoint_restoresStateFromBaseAndDelta() {
        service = newService(fakeL3, 10, 20);

        TaskState task = service.createTask("delta recovery test", "ns");
        DagNode first = service.appendNode(task.getTaskId(), "THOUGHT", "before full");
        String fullCheckpointId = service.checkpoint(task.getTaskId());

        DagNode second = service.appendNodeWithTarget(task.getTaskId(), "ACTION", "after full",
                first.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);
        service.updateContext(task.getTaskId(), "delta-key", "delta-value");
        String firstDeltaCheckpointId = service.checkpoint(task.getTaskId());

        DagNode third = service.appendNode(task.getTaskId(), "THOUGHT", "after second delta");
        service.updateContext(task.getTaskId(), "delta-key-2", "delta-value-2");
        String secondDeltaCheckpointId = service.checkpoint(task.getTaskId());

        List<CheckpointMetadata> checkpoints = service.listCheckpoints(task.getTaskId());
        assertThat(checkpoints)
                .extracting(CheckpointMetadata::getType)
                .containsExactly(
                        CheckpointMetadata.CheckpointType.FULL,
                        CheckpointMetadata.CheckpointType.DELTA,
                        CheckpointMetadata.CheckpointType.DELTA);
        assertThat(checkpoints.get(1).getBaseCheckpointId()).isEqualTo(fullCheckpointId);
        assertThat(checkpoints.get(2).getBaseCheckpointId()).isEqualTo(firstDeltaCheckpointId);

        TaskState recovered = service.recover(task.getTaskId(), secondDeltaCheckpointId);

        assertThat(firstDeltaCheckpointId).isNotEqualTo(fullCheckpointId);
        assertThat(secondDeltaCheckpointId).isNotEqualTo(firstDeltaCheckpointId);
        assertThat(recovered.getLatestCheckpointId()).isEqualTo(secondDeltaCheckpointId);
        assertThat(recovered.getGraph().getNode(first.getNodeId())).isPresent();
        assertThat(recovered.getGraph().getNode(second.getNodeId())).isPresent();
        assertThat(recovered.getGraph().getNode(third.getNodeId())).isPresent();
        assertThat(recovered.getGraph().areConnected(first.getNodeId(), second.getNodeId())).isTrue();
        assertThat(recovered.getContext()).containsEntry("delta-key", "delta-value");
        assertThat(recovered.getContext()).containsEntry("delta-key-2", "delta-value-2");
        assertThat(counterValue("vortex.checkpoint.recovery.total", "outcome", "success", "mode", "DELTA_CHAIN"))
                .isEqualTo(1.0);
    }

    @Test
    void recover_fromDeltaCheckpoint_restoresRuntimeStateDiffs() {
        service = newService(fakeL3, 10, 20);

        TaskState task = service.createTask("runtime delta recovery", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before full");
        String fullCheckpointId = service.checkpoint(task.getTaskId());

        service.appendConversationMessage(task.getTaskId(), "conversation-delta", "assistant", "captured in delta");
        service.startToolExecution(task.getTaskId(), "tool-delta", "http", "GET /health");
        service.failToolExecution(task.getTaskId(), "tool-delta", "503");
        service.startLlmCall(task.getTaskId(), "llm-delta", "openai", "gpt-test", "recover me", 500L);
        service.timeoutLlmCall(task.getTaskId(), "llm-delta", "timeout");
        String deltaCheckpointId = service.checkpoint(task.getTaskId());

        assertThat(deltaCheckpointId).isNotEqualTo(fullCheckpointId);
        assertThat(service.listCheckpoints(task.getTaskId()))
                .extracting(CheckpointMetadata::getType)
                .containsExactly(CheckpointMetadata.CheckpointType.FULL, CheckpointMetadata.CheckpointType.DELTA);

        service.evictFromCacheForTest(task.getTaskId());
        TaskState recovered = service.recover(task.getTaskId(), deltaCheckpointId);

        assertThat(recovered.getConversations().get("conversation-delta").getMessages())
                .extracting(ConversationMessage::getContent)
                .containsExactly("captured in delta");
        assertThat(recovered.getToolExecutions().get("tool-delta").getStatus())
                .isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(recovered.getLlmCalls().get("llm-delta").getStatus())
                .isEqualTo(LlmCallStatus.TIMED_OUT);
        assertThat(recovered.getLlmCalls().get("llm-delta").isRetryable()).isTrue();
    }
    @Test
    void recover_fromFullCheckpoint_recordsFullRecoveryMetric() {
        TaskState task = service.createTask("full recovery metrics", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "full");
        String checkpointId = service.checkpoint(task.getTaskId());

        TaskState recovered = service.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getLatestCheckpointId()).isEqualTo(checkpointId);
        assertThat(counterValue("vortex.checkpoint.recovery.total", "outcome", "success", "mode", "FULL"))
                .isEqualTo(1.0);
    }

    @Test
    void recover_updatesMemorySloTrackerForSuccessAndFailure() {
        TaskState task = service.createTask("recovery slo", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "base");
        String checkpointId = service.checkpoint(task.getTaskId());

        TaskState recovered = service.recover(task.getTaskId(), checkpointId);
        assertThat(recovered.getLatestCheckpointId()).isEqualTo(checkpointId);
        assertThat(memorySloTracker.snapshot().recoverySuccessRate()).isEqualTo(1.0);

        assertThatThrownBy(() -> service.recover(task.getTaskId(), "missing-checkpoint"))
                .isInstanceOf(CheckpointRecoveryException.class);

        assertThat(memorySloTracker.snapshot().recoverySuccessRate()).isEqualTo(0.5);
    }

    @Test
    void recover_missingDeltaBase_throwsTypedFailureAndRecordsMetric() {
        service = newService(fakeL3, 10, 20);

        TaskState task = service.createTask("broken delta chain", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before full");
        service.checkpoint(task.getTaskId());
        service.appendNode(task.getTaskId(), "ACTION", "delta");
        String deltaCheckpointId = service.checkpoint(task.getTaskId());

        CheckpointMetadata delta = fakeL3.listCheckpointMetadata(task.getTaskId()).stream()
                .filter(meta -> meta.getCheckpointId().equals(deltaCheckpointId))
                .findFirst()
                .orElseThrow();
        fakeL3.deleteCheckpoint(task.getTaskId() + "/" + delta.getBaseCheckpointId());

        assertThatThrownBy(() -> service.recover(task.getTaskId(), deltaCheckpointId))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.DELTA_CHAIN_BROKEN));

        assertThat(counterValue("vortex.checkpoint.recovery.total",
                "outcome", "failure", "mode", "NONE", "reason", "DELTA_CHAIN_BROKEN"))
                .isEqualTo(1.0);
    }

    @Test
    void recover_missingDeltaPayload_throwsTypedFailureAndRecordsMetric() {
        service = newService(fakeL3, 10, 20);

        TaskState task = service.createTask("missing delta payload", "ns");
        DagNode first = service.appendNode(task.getTaskId(), "THOUGHT", "before full");
        service.checkpoint(task.getTaskId());
        service.appendNodeWithTarget(task.getTaskId(), "ACTION", "delta",
                first.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);
        String deltaCheckpointId = service.checkpoint(task.getTaskId());

        fakeL3.deleteRawBytes("checkpoints/" + task.getTaskId() + "/" + deltaCheckpointId + ".kryo");

        assertThatThrownBy(() -> service.recover(task.getTaskId(), deltaCheckpointId))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.DELTA_PAYLOAD_MISSING));

        assertThat(counterValue("vortex.checkpoint.recovery.total",
                "outcome", "failure", "mode", "NONE", "reason", "DELTA_PAYLOAD_MISSING"))
                .isEqualTo(1.0);
    }

    @Test
    void checkpointRotation_resetsDeltaCountAfterNewFull() {
        service = newService(fakeL3, 2, 20);

        TaskState task = service.createTask("checkpoint rotation test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "n1");
        service.checkpoint(task.getTaskId());

        service.appendNode(task.getTaskId(), "THOUGHT", "n2");
        service.checkpoint(task.getTaskId());

        service.appendNode(task.getTaskId(), "THOUGHT", "n3");
        service.checkpoint(task.getTaskId());

        service.appendNode(task.getTaskId(), "THOUGHT", "n4");
        service.checkpoint(task.getTaskId());

        service.appendNode(task.getTaskId(), "THOUGHT", "n5");
        service.checkpoint(task.getTaskId());

        assertThat(service.listCheckpoints(task.getTaskId()))
                .extracting(CheckpointMetadata::getType)
                .containsExactly(
                        CheckpointMetadata.CheckpointType.FULL,
                        CheckpointMetadata.CheckpointType.DELTA,
                        CheckpointMetadata.CheckpointType.DELTA,
                        CheckpointMetadata.CheckpointType.FULL,
                        CheckpointMetadata.CheckpointType.DELTA);
    }

    @Test
    void retentionReloadsCheckpointHistoryAfterDeletion() {
        service = newService(fakeL3, 10, 2);
        Instant sharedCreatedAt = Instant.parse("2026-08-21T00:00:00Z");

        TaskState task = service.createTask("retention test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "n1");
        String firstCheckpointId = service.checkpoint(task.getTaskId());
        fakeL3.metadata.get(task.getTaskId() + "/" + firstCheckpointId).setCreatedAt(sharedCreatedAt);

        service.appendNode(task.getTaskId(), "THOUGHT", "n2");
        String secondCheckpointId = service.checkpoint(task.getTaskId());
        fakeL3.metadata.get(task.getTaskId() + "/" + secondCheckpointId).setCreatedAt(sharedCreatedAt);

        service.appendNode(task.getTaskId(), "THOUGHT", "n3");
        String thirdCheckpointId = service.checkpoint(task.getTaskId());
        fakeL3.metadata.get(task.getTaskId() + "/" + thirdCheckpointId).setCreatedAt(sharedCreatedAt);
        checkpointManager.reloadTask(task.getTaskId());

        List<CheckpointMetadata> listedByService = service.listCheckpoints(task.getTaskId());
        List<CheckpointMetadata> listedByStore = fakeL3.listCheckpointMetadata(task.getTaskId());

        assertThat(listedByService).hasSize(3);
        assertThat(listedByStore).hasSize(3);
        assertThat(listedByService)
                .extracting(CheckpointMetadata::getCheckpointId)
                .containsExactlyElementsOf(listedByStore.stream()
                        .sorted(CheckpointMetadata.chronologicalOrder())
                        .map(CheckpointMetadata::getCheckpointId)
                        .toList());

        TaskState recovered = service.recover(task.getTaskId(), listedByService.getLast().getCheckpointId());
        assertThat(recovered.getGraph().nodeCount()).isEqualTo(3);
    }

    @Test
    void retention_preservesLatestCheckpointEvenWhenAllAreExpired() {
        TaskState task = TaskState.builder()
                .taskId("retention-latest")
                .description("retention latest protection")
                .graph(new DagGraph())
                .build();

        CheckpointMetadata full = CheckpointMetadata.builder()
                .checkpointId("full-old")
                .taskId(task.getTaskId())
                .type(CheckpointMetadata.CheckpointType.FULL)
                .createdAt(Instant.now().minus(Duration.ofDays(3)))
                .build();
        fakeL3.saveCheckpointWithMetadata(task, full);

        CheckpointDelta deltaPayload = new CheckpointDelta(
                "full-old",
                0,
                Set.of(),
                Set.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                Set.of(),
                null,
                null,
                java.util.List.of(),
                TaskState.TaskStatus.RUNNING,
                TaskState.TaskFinalizationStatus.NONE);
        CheckpointMetadata delta = CheckpointMetadata.builder()
                .checkpointId("delta-old")
                .taskId(task.getTaskId())
                .type(CheckpointMetadata.CheckpointType.DELTA)
                .baseCheckpointId("full-old")
                .createdAt(Instant.now().minus(Duration.ofDays(2)))
                .build();
        fakeL3.saveCheckpointBytesWithMetadata(new KryoSerializer().serializeCompressed(deltaPayload), delta);

        fakeL3.metadata.get(task.getTaskId() + "/full-old").setCreatedAt(Instant.now().minus(Duration.ofDays(3)));
        fakeL3.metadata.get(task.getTaskId() + "/delta-old").setCreatedAt(Instant.now().minus(Duration.ofDays(2)));

        CheckpointLifecycleManager lifecycleManager = new CheckpointLifecycleManager(fakeL3, 20, 0, 48);
        lifecycleManager.applyRetention(task.getTaskId(), fakeL3.listCheckpointMetadata(task.getTaskId()));

        assertThat(fakeL3.listCheckpointMetadata(task.getTaskId()))
                .extracting(CheckpointMetadata::getCheckpointId)
                .containsExactly("full-old", "delta-old");
    }

    @Test
    void taskNotFound_throwsOnAppend() {
        assertThatThrownBy(() -> service.appendNode("nonexistent", "THOUGHT", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task not found");
    }

    @Test
    void completeTask_marksCompleted() {
        TaskState task = service.createTask("completion test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "done");
        service.completeTask(task.getTaskId());

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(task.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
        assertThat(service.getTask(task.getTaskId())).get()
                .satisfies(recovered -> {
                    assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
                    assertThat(recovered.getFinalizationStatus())
                            .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
                });
        assertThat(service.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(task.getTaskId());
    }

    @Test
    void failTask_persistsRecoverableFailedState() {
        TaskState task = service.createTask("failure test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "fail me");
        service.failTask(task.getTaskId());

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
        assertThat(task.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
        assertThat(service.getTask(task.getTaskId())).get()
                .satisfies(recovered -> {
                    assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
                    assertThat(recovered.getFinalizationStatus())
                            .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
                });
        assertThat(service.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(task.getTaskId());
    }

    @Test
    void completeTask_finalCheckpointFailureWithoutPriorCheckpoint_remainsRecoverableAndRetryable() {
        FailingCheckpointStore failingStore = new FailingCheckpointStore(new FakeL3ColdStore());
        service = newService(failingStore);

        TaskState task = service.createTask("completion retry", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "complete me");
        failingStore.failOnCheckpointWriteNumber(2);

        assertThatThrownBy(() -> service.completeTask(task.getTaskId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated checkpoint");

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(task.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        assertThat(service.listCheckpoints(task.getTaskId())).hasSize(1);
        assertThat(walReader.readAll(task.getTaskId()))
                .extracting(ActionLogEntry::getOperation)
                .containsExactly(ActionLogEntry.OperationType.SET_STATUS);
        assertThat(service.getTask(task.getTaskId())).get()
                .extracting(TaskState::getFinalizationStatus)
                .isEqualTo(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        assertThat(service.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(task.getTaskId());
        assertThat(isTaskRegisteredWithScheduler(task.getTaskId())).isFalse();

        TaskState recovered = service.recover(task.getTaskId(), null);
        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(recovered.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        assertThat(isTaskRegisteredWithScheduler(task.getTaskId())).isFalse();

        service.completeTask(task.getTaskId());

        assertThat(service.listCheckpoints(task.getTaskId())).hasSize(2);
        assertThat(walReader.readAll(task.getTaskId())).isEmpty();
        assertThat(service.getTask(task.getTaskId())).get()
                .satisfies(finalized -> {
                    assertThat(finalized.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
                    assertThat(finalized.getFinalizationStatus())
                            .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
                });
    }

    @Test
    void failTask_finalCheckpointFailureWithoutPriorCheckpoint_remainsRecoverableAndRetryable() {
        FailingCheckpointStore failingStore = new FailingCheckpointStore(new FakeL3ColdStore());
        service = newService(failingStore);

        TaskState task = service.createTask("failure retry", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "fail me");
        failingStore.failOnCheckpointWriteNumber(2);

        assertThatThrownBy(() -> service.failTask(task.getTaskId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated checkpoint");

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
        assertThat(task.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        assertThat(service.listCheckpoints(task.getTaskId())).hasSize(1);
        assertThat(walReader.readAll(task.getTaskId()))
                .extracting(ActionLogEntry::getOperation)
                .containsExactly(ActionLogEntry.OperationType.SET_STATUS);
        assertThat(service.getTask(task.getTaskId())).get()
                .extracting(TaskState::getFinalizationStatus)
                .isEqualTo(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        assertThat(service.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(task.getTaskId());
        assertThat(isTaskRegisteredWithScheduler(task.getTaskId())).isFalse();

        TaskState recovered = service.recover(task.getTaskId(), null);
        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
        assertThat(recovered.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        assertThat(isTaskRegisteredWithScheduler(task.getTaskId())).isFalse();

        service.failTask(task.getTaskId());

        assertThat(service.listCheckpoints(task.getTaskId())).hasSize(2);
        assertThat(walReader.readAll(task.getTaskId())).isEmpty();
        assertThat(service.getTask(task.getTaskId())).get()
                .satisfies(finalized -> {
                    assertThat(finalized.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
                    assertThat(finalized.getFinalizationStatus())
                            .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
                });
    }

    @Test
    void completeTask_cleanupFailureAfterFinalCheckpoint_staysFinalizedAndRetryable() {
        FailingActionLogWriter failingWalWriter = new FailingActionLogWriter(tempDir.resolve("wal").toString());
        service = newService(fakeL3, failingWalWriter);

        TaskState task = service.createTask("completion cleanup retry", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "complete me");
        failingWalWriter.failNextClose();

        assertThatThrownBy(() -> service.completeTask(task.getTaskId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAL close failed");

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(task.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
        assertThat(service.getTask(task.getTaskId())).get()
                .satisfies(recovered -> {
                    assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
                    assertThat(recovered.getFinalizationStatus())
                            .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
                });
        int checkpointsAfterFailure = service.listCheckpoints(task.getTaskId()).size();
        assertThat(service.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(task.getTaskId());
        assertThat(isTaskRegisteredWithScheduler(task.getTaskId())).isFalse();
        assertThat(walReader.exists(task.getTaskId())).isTrue();

        service.completeTask(task.getTaskId());

        assertThat(service.listCheckpoints(task.getTaskId())).hasSize(checkpointsAfterFailure);
        assertThat(walReader.exists(task.getTaskId())).isFalse();
        assertThat(service.getTask(task.getTaskId())).get()
                .extracting(TaskState::getFinalizationStatus)
                .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
    }

    @Test
    void failTask_cleanupFailureAfterFinalCheckpoint_staysFinalizedAndRetryable() {
        FailingActionLogWriter failingWalWriter = new FailingActionLogWriter(tempDir.resolve("wal").toString());
        service = newService(fakeL3, failingWalWriter);

        TaskState task = service.createTask("failure cleanup retry", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "fail me");
        failingWalWriter.failNextClose();

        assertThatThrownBy(() -> service.failTask(task.getTaskId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAL close failed");

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
        assertThat(task.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
        assertThat(service.getTask(task.getTaskId())).get()
                .satisfies(recovered -> {
                    assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
                    assertThat(recovered.getFinalizationStatus())
                            .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
                });
        int checkpointsAfterFailure = service.listCheckpoints(task.getTaskId()).size();
        assertThat(service.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(task.getTaskId());
        assertThat(isTaskRegisteredWithScheduler(task.getTaskId())).isFalse();
        assertThat(walReader.exists(task.getTaskId())).isTrue();

        service.failTask(task.getTaskId());

        assertThat(service.listCheckpoints(task.getTaskId())).hasSize(checkpointsAfterFailure);
        assertThat(walReader.exists(task.getTaskId())).isFalse();
        assertThat(service.getTask(task.getTaskId())).get()
                .extracting(TaskState::getFinalizationStatus)
                .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
    }

    @Test
    void deleteTask_removesDurableStateAndHidesTaskFromRecoveryAndListings() {
        TaskState task = service.createTask("delete test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "persist then delete");
        service.checkpoint(task.getTaskId());

        assertThat(service.deleteTask(task.getTaskId())).isTrue();

        assertThat(service.getTask(task.getTaskId())).isEmpty();
        assertThat(service.listCheckpoints(task.getTaskId())).isEmpty();
        assertThat(service.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(task.getTaskId());
        assertThat(walReader.exists(task.getTaskId())).isFalse();
        assertThatThrownBy(() -> service.recover(task.getTaskId(), null))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.NO_CHECKPOINT_AVAILABLE));
    }

    @Test
    void deleteTask_cleanupFailureAfterDeleteIntent_staysDeletedAndRetryableAcrossRestart() {
        FailingActionLogWriter failingWalWriter = new FailingActionLogWriter(tempDir.resolve("wal").toString());
        service = newService(fakeL3, failingWalWriter);

        TaskState task = service.createTask("delete cleanup retry", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "delete me");
        service.checkpoint(task.getTaskId());
        failingWalWriter.failNextClose();

        assertThatThrownBy(() -> service.deleteTask(task.getTaskId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAL close failed");

        assertThat(service.getTask(task.getTaskId())).isEmpty();
        assertThat(service.listCheckpoints(task.getTaskId())).isEmpty();
        assertThat(service.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(task.getTaskId());
        assertThat(walReader.exists(task.getTaskId())).isTrue();

        service = newService(fakeL3);

        assertThat(service.getTask(task.getTaskId())).isEmpty();
        assertThat(service.listCheckpoints(task.getTaskId())).isEmpty();
        assertThatThrownBy(() -> service.recover(task.getTaskId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task deleted");

        assertThat(service.deleteTask(task.getTaskId())).isTrue();
        assertThat(walReader.exists(task.getTaskId())).isFalse();
    }

    @Test
    void exportDag_validDotFormat() {
        TaskState task = service.createTask("viz test", "ns");
        DagNode a = service.appendNode(task.getTaskId(), "THOUGHT", "start");
        service.appendNodeWithTarget(task.getTaskId(), "ACTION", "execute",
                a.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);

        String dot = service.exportDag(task.getTaskId());
        assertThat(dot).startsWith("digraph Task_");
        assertThat(dot).contains("->");
        assertThat(dot).contains("label=");
        assertThat(dot).endsWith("}\n");
    }

    @Test
    void updateContext_addsAndChangesValue() {
        TaskState task = service.createTask("ctx test", "ns");
        service.updateContext(task.getTaskId(), "key1", "value1");
        assertThat(task.getContext()).containsEntry("key1", "value1");

        service.updateContext(task.getTaskId(), "key1", "value2");
        assertThat(task.getContext()).containsEntry("key1", "value2");
    }

    @Test
    void listActiveTasks_returnsAll() {
        service.createTask("task A", "ns");
        service.createTask("task B", "ns");
        assertThat(service.listActiveTasks()).hasSize(2);
    }

    @Test
    void listActiveTasks_paginatesInReverseCreationOrder() {
        TaskState oldest = service.createTask("task A", "ns");
        TaskState newest = service.createTask("task B", "ns");
        oldest.setCreatedAt(Instant.parse("2026-05-25T00:00:00Z"));
        newest.setCreatedAt(Instant.parse("2026-05-25T00:00:10Z"));

        TaskLifecycleManager.TaskPage page = service.listActiveTasks(0, 1);

        assertThat(page.items()).extracting(TaskState::getTaskId).containsExactly(newest.getTaskId());
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void listActiveTasks_includesCheckpointedTasksEvictedFromCache() {
        TaskState task = service.createTask("checkpointed task", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "persist me");
        service.checkpoint(task.getTaskId());

        service.evictFromCacheForTest(task.getTaskId());

        TaskLifecycleManager.TaskPage page = service.listActiveTasks(0, 10);

        assertThat(page.items()).extracting(TaskState::getTaskId).contains(task.getTaskId());
    }

    @Test
    void failTask_isIdempotentForRecoveredFailedTask() {
        TaskState task = service.createTask("failure retry", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "first");
        service.failTask(task.getTaskId());

        long seqBefore = walWriter.currentSequenceNumber(task.getTaskId());
        int walEntriesBefore = walReader.readAll(task.getTaskId()).size();

        service.failTask(task.getTaskId());

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
    }

    @Test
    void createBranch_createsAndListsBranch() {
        TaskState task = service.createTask("branch test", "ns");
        DagNode n1 = service.appendNode(task.getTaskId(), "THOUGHT", "fork point");

        TaskBranch branch = service.createBranch(task.getTaskId(), "alt-plan", n1.getNodeId());
        assertThat(branch.getBranchName()).isEqualTo("alt-plan");
        assertThat(branch.getStatus()).isEqualTo(TaskBranch.BranchStatus.ACTIVE);
        assertThat(service.listBranches(task.getTaskId())).hasSize(1);
    }

    @Test
    void createBranch_missingSource_doesNotWriteWal() {
        TaskState task = service.createTask("branch create failure", "ns");
        long seqBefore = walWriter.currentSequenceNumber(task.getTaskId());
        int walEntriesBefore = walReader.readAll(task.getTaskId()).size();

        assertThatThrownBy(() -> service.createBranch(task.getTaskId(), "alt-plan", "missing-node"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source node not found");

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
        assertThat(service.listBranches(task.getTaskId())).isEmpty();
    }

    @Test
    void switchBranch_missingBranch_doesNotWriteWal() {
        TaskState task = service.createTask("branch switch failure", "ns");
        DagNode n1 = service.appendNode(task.getTaskId(), "THOUGHT", "fork point");
        service.createBranch(task.getTaskId(), "alt-plan", n1.getNodeId());
        long seqBefore = walWriter.currentSequenceNumber(task.getTaskId());
        int walEntriesBefore = walReader.readAll(task.getTaskId()).size();

        assertThatThrownBy(() -> service.switchBranch(task.getTaskId(), "missing-branch"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Active branch not found");

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
    }

    @Test
    void recover_preservesBranchStateAfterCheckpointAndSwitch() {
        TaskState task = service.createTask("branch recovery", "ns");
        DagNode forkPoint = service.appendNode(task.getTaskId(), "THOUGHT", "fork point");
        service.checkpoint(task.getTaskId());

        TaskBranch branch = service.createBranch(task.getTaskId(), "alt-plan", forkPoint.getNodeId());
        service.switchBranch(task.getTaskId(), branch.getBranchId());

        service.evictFromCacheForTest(task.getTaskId());

        TaskState recovered = service.recover(task.getTaskId(), task.getLatestCheckpointId());

        assertThat(recovered.getBranches())
                .extracting(TaskBranch::getBranchId)
                .contains(branch.getBranchId());
        assertThat(recovered.getCurrentBranchId()).isEqualTo(branch.getBranchId());
        assertThat(recovered.getGraph().getNodes().values())
                .extracting(DagNode::getType)
                .contains(DagNode.NodeType.FORK);
    }

    @Test
    void exportDag_withBranchId_filtersToSelectedBranch() {
        TaskState task = service.createTask("branch export", "ns");
        DagNode root = service.appendNode(task.getTaskId(), "THOUGHT", "root");

        TaskBranch branchA = service.createBranch(task.getTaskId(), "alpha", root.getNodeId());
        DagNode alphaNode = service.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "alpha-step", branchA.getForkNodeId(), DagEdge.EdgeType.CONTROL_DEP);

        TaskBranch branchB = service.createBranch(task.getTaskId(), "beta", root.getNodeId());
        DagNode betaNode = service.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "beta-step", branchB.getForkNodeId(), DagEdge.EdgeType.CONTROL_DEP);

        String alphaDot = service.exportDag(task.getTaskId(), branchA.getBranchId());

        assertThat(alphaDot).contains(root.getNodeId(), branchA.getForkNodeId(), alphaNode.getNodeId());
        assertThat(alphaDot).doesNotContain(branchB.getForkNodeId(), betaNode.getNodeId());
    }

    @Test
    void onTaskEvicted_createsEmergencyCheckpointWithoutReentrantLookup() throws Exception {
        TaskState task = service.createTask("eviction test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "pending checkpoint");

        java.lang.reflect.Field tlmField = SnapshotService.class.getDeclaredField("taskLifecycleManager");
        tlmField.setAccessible(true);
        TaskLifecycleManager tlm = (TaskLifecycleManager) tlmField.get(service);
        service.evictFromCacheForTest(task.getTaskId());
        assertThat(tlm.getCachedTask(task.getTaskId())).isEmpty();
        Method onTaskEvicted = TaskLifecycleManager.class.getDeclaredMethod(
                "onTaskEvicted", String.class, TaskState.class, RemovalCause.class);
        onTaskEvicted.setAccessible(true);
        onTaskEvicted.invoke(tlm, task.getTaskId(), task, RemovalCause.SIZE);

        assertThat(task.getLatestCheckpointId()).isNotNull();
        assertThat(service.listCheckpoints(task.getTaskId()))
                .extracting(CheckpointMetadata::getCheckpointId)
                .contains(task.getLatestCheckpointId());
    }

    @Test
    void onTaskEvicted_createsEmergencyCheckpointForDirtyStateAfterPreviousCheckpoint() throws Exception {
        TaskState task = service.createTask("dirty eviction test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "first node");
        service.checkpoint(task.getTaskId());

        service.appendNode(task.getTaskId(), "ACTION", "second node after checkpoint");

        int checkpointsBefore = service.listCheckpoints(task.getTaskId()).size();

        java.lang.reflect.Field tlmField = SnapshotService.class.getDeclaredField("taskLifecycleManager");
        tlmField.setAccessible(true);
        TaskLifecycleManager tlm = (TaskLifecycleManager) tlmField.get(service);
        service.evictFromCacheForTest(task.getTaskId());
        assertThat(tlm.getCachedTask(task.getTaskId())).isEmpty();
        Method onTaskEvicted = TaskLifecycleManager.class.getDeclaredMethod(
                "onTaskEvicted", String.class, TaskState.class, RemovalCause.class);
        onTaskEvicted.setAccessible(true);
        onTaskEvicted.invoke(tlm, task.getTaskId(), task, RemovalCause.SIZE);

        assertThat(service.listCheckpoints(task.getTaskId()).size()).isGreaterThan(checkpointsBefore);
    }

    @Test
    void checkpointFailure_restoresDirtyTrackingForRetry() {
        FailingCheckpointStore failingStore = new FailingCheckpointStore(fakeL3);
        service = newService(failingStore);

        TaskState task = service.createTask("checkpoint failure restore", "ns");
        DagNode node = service.appendNode(task.getTaskId(), "THOUGHT", "must stay dirty");

        failingStore.failNextCheckpointWrite();

        assertThatThrownBy(() -> service.checkpoint(task.getTaskId()))
                .isInstanceOf(RuntimeException.class);

        assertThat(dirtySetTracker.hasDirty(task.getTaskId())).isTrue();

        failingStore.stopFailing();
        service.checkpoint(task.getTaskId());

        assertThat(dirtySetTracker.hasDirty(task.getTaskId())).isFalse();
        assertThat(service.listCheckpoints(task.getTaskId())).hasSize(1);
        assertThat(task.getGraph().getNode(node.getNodeId())).isPresent();
    }

    @Test
    void recover_whenCheckpointMetadataReadFails_throwsTypedFailure() {
        FailingCheckpointStore failingStore = new FailingCheckpointStore(fakeL3);
        service = newService(failingStore);

        TaskState task = service.createTask("metadata read failure", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "base");
        String checkpointId = service.checkpoint(task.getTaskId());

        failingStore.failMetadataReads();

        assertThatThrownBy(() -> service.recover(task.getTaskId(), checkpointId))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.CHECKPOINT_METADATA_LOAD_FAILED));
    }

    @Test
    void recover_whenFullCheckpointReadFails_throwsTypedFailure() {
        FailingCheckpointStore failingStore = new FailingCheckpointStore(fakeL3);
        service = newService(failingStore);

        TaskState task = service.createTask("full checkpoint read failure", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "base");
        String checkpointId = service.checkpoint(task.getTaskId());

        failingStore.failCheckpointLoads();

        assertThatThrownBy(() -> service.recover(task.getTaskId(), checkpointId))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.CHECKPOINT_STORAGE_READ_FAILED));
    }

    @Test
    void getTask_whenLazyRecoveryFails_rethrowsTypedFailure() {
        FailingCheckpointStore failingStore = new FailingCheckpointStore(fakeL3);
        service = newService(failingStore);

        TaskState task = service.createTask("lazy metadata read failure", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "base");
        service.checkpoint(task.getTaskId());
        service.evictFromCacheForTest(task.getTaskId());

        failingStore.failMetadataReads();

        assertThatThrownBy(() -> service.getTask(task.getTaskId()))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.CHECKPOINT_METADATA_LOAD_FAILED));
    }

    @Test
    void recover_withBrokenDeltaEdgeTarget_throwsTypedFailure() {
        service = newService(fakeL3, 10, 20);

        TaskState task = service.createTask("broken delta edge", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "base");
        service.checkpoint(task.getTaskId());

        DagNode orphan = DagNode.builder()
                .nodeId("orphan-node")
                .type(DagNode.NodeType.ACTION)
                .content("orphan")
                .status(DagNode.NodeStatus.PENDING)
                .build();
        DagEdge brokenEdge = DagEdge.builder()
                .edgeId("broken-edge")
                .sourceNodeId("missing-source")
                .targetNodeId(orphan.getNodeId())
                .dependencyType(DagEdge.EdgeType.CONTROL_DEP)
                .build();

        CheckpointMetadata deltaMeta = CheckpointMetadata.builder()
                .checkpointId("broken-delta")
                .taskId(task.getTaskId())
                .sequenceNumber(1)
                .type(CheckpointMetadata.CheckpointType.DELTA)
                .baseCheckpointId(task.getLatestCheckpointId())
                .build();
        CheckpointDelta brokenDelta = new CheckpointDelta(
                task.getLatestCheckpointId(),
                1,
                Set.of(orphan),
                Set.of(brokenEdge),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                Set.of(),
                orphan.getNodeId(),
                null,
                java.util.List.of(),
                TaskState.TaskStatus.RUNNING,
                TaskState.TaskFinalizationStatus.NONE);
        fakeL3.putBytes(
                "checkpoints/" + task.getTaskId() + "/broken-delta.kryo",
                new KryoSerializer().serializeCompressed(brokenDelta));
        fakeL3.saveCheckpointBytesWithMetadata(
                new KryoSerializer().serializeCompressed(brokenDelta),
                deltaMeta);

        assertThatThrownBy(() -> service.recover(task.getTaskId(), "broken-delta"))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.DELTA_STATE_APPLY_FAILED));
    }

    @Test
    void recover_withCorruptWalEntryBeforeEof_throwsTypedFailure() throws Exception {
        TaskState task = service.createTask("corrupt wal", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());
        service.appendNode(task.getTaskId(), "ACTION", "after checkpoint");

        Path walFile = walWriter.getWalFile(task.getTaskId());
        Files.writeString(walFile,
                "{\"sequenceNumber\":2,\"entryId\":\"broken\"\n" +
                        "{\"sequenceNumber\":3,\"entryId\":\"another\",\"operation\":\"UPDATE_CONTEXT\",\"payload\":\"{}\",\"timestamp\":\"2026-05-15T00:00:00Z\"}\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.recover(task.getTaskId(), checkpointId))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED));
    }

    @Test
    void recover_withCorruptWalPayload_throwsTypedFailure() {
        TaskState task = service.createTask("corrupt wal payload", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());

        walWriter.append(task.getTaskId(), ActionLogEntry.OperationType.UPDATE_CONTEXT, "{corrupt");

        assertThatThrownBy(() -> service.recover(task.getTaskId(), checkpointId))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED));
    }

    @Test
    void recover_withCompleteNodeReferencingMissingNode_throwsTypedFailure() {
        TaskState task = service.createTask("missing complete replay target", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());

        walWriter.append(task.getTaskId(), ActionLogEntry.OperationType.COMPLETE_NODE,
                "{\"nodeId\":\"missing-node\",\"result\":\"done\"}");

        assertThatThrownBy(() -> service.recover(task.getTaskId(), checkpointId))
                .isInstanceOf(CheckpointRecoveryException.class)
                .hasMessageContaining("missing-node")
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED));
    }

    @Test
    void recover_withWalReplayStateApplyFailure_abortsRecovery() {
        TaskState task = service.createTask("wal state apply failure", "ns");
        DagNode source = service.appendNode(task.getTaskId(), "THOUGHT", "source");
        service.checkpoint(task.getTaskId());

        walWriter.append(task.getTaskId(), ActionLogEntry.OperationType.ADD_EDGE,
                "{\"edgeId\":\"broken-edge\",\"sourceNodeId\":\"missing-source\",\"targetNodeId\":\""
                        + source.getNodeId() + "\",\"dependencyType\":\"CONTROL_DEP\",\"condition\":\"\"}");

        assertThatThrownBy(() -> service.recover(task.getTaskId(), task.getLatestCheckpointId()))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> assertThat(((CheckpointRecoveryException) ex).getReason())
                        .isEqualTo(CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED));

        assertThat(counterValue("vortex.checkpoint.recovery.total",
                "outcome", "failure", "mode", "NONE", "reason", "WAL_STATE_APPLY_FAILED"))
                .isEqualTo(1.0);
        assertThat(counterValue("vortex.checkpoint.recovery.total",
                "outcome", "success", "mode", "FULL"))
                .isEqualTo(0.0);
    }

    @Test
    void shutdownCheckpoint_skipsUnloadedTaskWithoutCreatingNewCheckpoint() {
        TaskState task = service.createTask("shutdown skip test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "loaded");
        String firstCheckpointId = service.checkpoint(task.getTaskId());

        service.evictFromCacheForTest(task.getTaskId());

        scheduler.shutdownCheckpoint();

        assertThat(service.listCheckpoints(task.getTaskId()))
                .extracting(CheckpointMetadata::getCheckpointId)
                .containsExactly(firstCheckpointId);
    }

    @Test
    void concurrentCheckpointRequests_areSerializedPerTask() throws Exception {
        TaskState task = service.createTask("concurrent checkpoint", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before concurrency");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        Runnable checkpointTask = () -> {
            ready.countDown();
            try {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                service.checkpoint(task.getTaskId());
            } catch (Throwable t) {
                failures.add(t);
            }
        };

        Thread t1 = new Thread(checkpointTask, "checkpoint-1");
        Thread t2 = new Thread(checkpointTask, "checkpoint-2");
        t1.start();
        t2.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        t1.join();
        t2.join();

        assertThat(failures).isEmpty();
        assertThat(service.listCheckpoints(task.getTaskId())).hasSize(2);
        assertThat(service.listCheckpoints(task.getTaskId()))
                .extracting(CheckpointMetadata::getSequenceNumber)
                .allMatch(seq -> seq == 1L);
    }

    private SnapshotService newService(FakeL3ColdStore store) {
        return newService(store, 10, 20);
    }

    private SnapshotService newService(FakeL3ColdStore store, int maxDeltasBeforeFull, int maxPerTask) {
        return newService(store, maxDeltasBeforeFull, maxPerTask, 7);
    }

    private SnapshotService newService(FakeL3ColdStore store, ActionLogWriter customWalWriter) {
        return newService(store, 10, 20, 7, false, 50, 60000, customWalWriter);
    }

    private SnapshotService newService(
            FakeL3ColdStore store, int maxDeltasBeforeFull, int maxPerTask, int maxAgeDays) {
        return newService(store, maxDeltasBeforeFull, maxPerTask, maxAgeDays, false, 50, 60000);
    }

    private SnapshotService newService(
            FakeL3ColdStore store,
            int maxDeltasBeforeFull,
            int maxPerTask,
            int maxAgeDays,
            boolean schedulerEnabled,
            int maxActionsBetween,
            long maxMillisBetween) {
        return newService(store, maxDeltasBeforeFull, maxPerTask, maxAgeDays,
                schedulerEnabled, maxActionsBetween, maxMillisBetween, null);
    }

    private SnapshotService newService(
            FakeL3ColdStore store,
            int maxDeltasBeforeFull,
            int maxPerTask,
            int maxAgeDays,
            boolean schedulerEnabled,
            int maxActionsBetween,
            long maxMillisBetween,
            ActionLogWriter customWalWriter) {
        String walDir = tempDir.resolve("wal").toString();
        walWriter = customWalWriter != null ? customWalWriter : new ActionLogWriter(walDir);
        walReader = new ActionLogReader(walDir);
        walTruncator = new ActionLogTruncator(walWriter, walReader, walDir);
        dirtySetTracker = new DirtySetTracker();
        checkpointManager = new IncrementalCheckpointManager(store, dirtySetTracker, maxDeltasBeforeFull);
        CheckpointLifecycleManager lifecycleManager = new CheckpointLifecycleManager(store, maxPerTask, maxAgeDays, 48);
        scheduler = new CheckpointScheduler(maxActionsBetween, maxMillisBetween, schedulerEnabled);
        conflictDetector = new BranchMergeConflictDetector();
        branchManager = new BranchManager(10, conflictDetector);
        dotExporter = new DotGraphExporter();
        meterRegistry = new SimpleMeterRegistry();
        checkpointRecoveryMetrics = new CheckpointRecoveryMetrics(meterRegistry);
        memorySloTracker = new MemorySloTracker(meterRegistry);
        memorySloTracker.bind();

        ApplicationEventPublisher eventPublisher = event -> {};

        // Create components with circular dependency resolution
        TaskLifecycleManager taskLifecycleMgr = new TaskLifecycleManager(
                store, checkpointManager, lifecycleManager, walWriter, walReader, walTruncator,
                scheduler, dirtySetTracker, memorySloTracker, new TaskFinalizationMetrics(meterRegistry), null);
        DagMutationService dagMutationSvc = new DagMutationService(
                walWriter, dirtySetTracker, scheduler, eventPublisher, branchManager, taskLifecycleMgr);
        RuntimeMutationService runtimeMutationSvc = new RuntimeMutationService(
                walWriter, dirtySetTracker, scheduler, taskLifecycleMgr);
        RecoveryEngine recoveryEng = new RecoveryEngine(
                walReader, walWriter, checkpointManager, checkpointRecoveryMetrics, memorySloTracker,
                branchManager, scheduler);

        SnapshotService snapshotService = new SnapshotService(
                taskLifecycleMgr, dagMutationSvc, runtimeMutationSvc, recoveryEng,
                branchManager, dotExporter, walWriter, walTruncator,
                checkpointManager, lifecycleManager, scheduler, checkpointRecoveryMetrics, memorySloTracker);
        taskLifecycleMgr.setRecoveryEngine(recoveryEng);
        return snapshotService;
    }

    private double counterValue(String meterName, String... tags) {
        if (meterRegistry == null) {
            return 0.0;
        }
        Search search = meterRegistry.find(meterName);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        var counter = search.counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void assertWalUnchanged(String taskId, long seqBefore, int walEntriesBefore) {
        assertThat(walWriter.currentSequenceNumber(taskId)).isEqualTo(seqBefore);
        assertThat(walReader.readAll(taskId)).hasSize(walEntriesBefore);
    }

    @SuppressWarnings("unchecked")
    private boolean isTaskRegisteredWithScheduler(String taskId) {
        try {
            java.lang.reflect.Field servicesField = CheckpointScheduler.class.getDeclaredField("taskServices");
            servicesField.setAccessible(true);
            Map<String, SnapshotService> services =
                    (Map<String, SnapshotService>) servicesField.get(scheduler);
            return services.containsKey(taskId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect scheduler registration", e);
        }
    }

    // ---- Fake L3 implementation for testing ----

    static class FakeL3ColdStore implements L3ColdStore {
        private final java.util.concurrent.ConcurrentHashMap<String, byte[]> store = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, CheckpointMetadata> metadata = new java.util.concurrent.ConcurrentHashMap<>();
        private final KryoSerializer serializer = new KryoSerializer();

        @Override
        public void archiveFragment(MemoryFragment fragment) {
            store.put("frag/" + fragment.getId(), serializer.serialize(fragment));
        }

        @Override
        public Optional<MemoryFragment> retrieveFragment(String id) {
            return Optional.ofNullable(store.get("frag/" + id))
                    .map(bytes -> serializer.deserialize(bytes, MemoryFragment.class));
        }

        @Override
        public String saveCheckpoint(TaskState state) {
            String cpId = state.getLatestCheckpointId() != null
                    ? state.getLatestCheckpointId()
                    : java.util.UUID.randomUUID().toString();
            state.setLatestCheckpointId(cpId);
            store.put("cp/" + state.getTaskId() + "/" + cpId, serializer.serialize(state));
            metadata.put(state.getTaskId() + "/" + cpId, CheckpointMetadata.builder()
                    .checkpointId(cpId)
                    .taskId(state.getTaskId())
                    .sequenceNumber(state.getWalSequenceNumber())
                    .type(CheckpointMetadata.CheckpointType.FULL)
                    .nodeCount(state.getGraph().nodeCount())
                    .edgeCount(state.getGraph().edgeCount())
                    .createdAt(state.getLastCheckpointAt())
                    .l3Key("cp/" + state.getTaskId() + "/" + cpId)
                    .build());
            return cpId;
        }

        @Override
        public CheckpointMetadata saveCheckpointWithMetadata(TaskState state, CheckpointMetadata meta) {
            String cpId = meta.getCheckpointId();
            state.setLatestCheckpointId(cpId);
            store.put("cp/" + state.getTaskId() + "/" + cpId, serializer.serialize(state));
            if (meta.getCreatedAt() == null) {
                meta.setCreatedAt(state.getLastCheckpointAt());
            }
            meta.setL3Key("cp/" + state.getTaskId() + "/" + cpId);
            metadata.put(state.getTaskId() + "/" + cpId, meta);
            return meta;
        }

        @Override
        public CheckpointMetadata saveCheckpointBytesWithMetadata(byte[] data, CheckpointMetadata meta) {
            String key = "checkpoints/" + meta.getTaskId() + "/" + meta.getCheckpointId() + ".kryo";
            store.put(key, data);
            if (meta.getCreatedAt() == null) {
                meta.setCreatedAt(java.time.Instant.now());
            }
            meta.setL3Key(key);
            metadata.put(meta.getTaskId() + "/" + meta.getCheckpointId(), meta);
            return meta;
        }

        @Override
        public Optional<TaskState> loadCheckpoint(String checkpointId) {
            // checkpointId format: taskId/uuid
            String key = "cp/" + checkpointId;
            return Optional.ofNullable(store.get(key))
                    .map(bytes -> serializer.deserialize(bytes, TaskState.class));
        }

        @Override
        public void deleteCheckpoint(String checkpointId) {
            store.remove("cp/" + checkpointId);
            store.remove("checkpoints/" + checkpointId + ".kryo");
            metadata.remove(checkpointId);
        }

        @Override
        public void putBytes(String key, byte[] data) {
            store.put(key, data);
        }

        @Override
        public byte[] getBytes(String key) {
            return store.get(key);
        }

        void deleteRawBytes(String key) {
            store.remove(key);
        }

        @Override
        public List<CheckpointMetadata> listCheckpointMetadata(String taskId) {
            return metadata.values().stream()
                    .filter(meta -> taskId.equals(meta.getTaskId()))
                    .sorted(java.util.Comparator.comparing(CheckpointMetadata::getCreatedAt))
                    .toList();
        }

        @Override
        public java.util.Set<String> listTaskIdsWithCheckpoints() {
            return metadata.values().stream()
                    .map(CheckpointMetadata::getTaskId)
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    static class FailingCheckpointStore extends FakeL3ColdStore {
        private final FakeL3ColdStore delegate;
        private volatile int checkpointWriteCount;
        private volatile Integer failOnCheckpointWriteNumber;
        private volatile boolean failMetadataReads;
        private volatile boolean failCheckpointLoads;

        FailingCheckpointStore(FakeL3ColdStore delegate) {
            this.delegate = delegate;
        }

        void failNextCheckpointWrite() {
            this.failOnCheckpointWriteNumber = checkpointWriteCount + 1;
        }

        void failOnCheckpointWriteNumber(int writeNumber) {
            this.failOnCheckpointWriteNumber = writeNumber;
        }

        void stopFailing() {
            this.failOnCheckpointWriteNumber = null;
        }

        void failMetadataReads() {
            this.failMetadataReads = true;
        }

        void failCheckpointLoads() {
            this.failCheckpointLoads = true;
        }

        @Override
        public CheckpointMetadata saveCheckpointWithMetadata(TaskState state, CheckpointMetadata meta) {
            checkpointWriteCount++;
            if (shouldFailCheckpointWrite()) {
                throw new IllegalStateException("simulated checkpoint write failure");
            }
            return delegate.saveCheckpointWithMetadata(state, meta);
        }

        @Override
        public CheckpointMetadata saveCheckpointBytesWithMetadata(byte[] data, CheckpointMetadata meta) {
            checkpointWriteCount++;
            if (shouldFailCheckpointWrite()) {
                throw new IllegalStateException("simulated checkpoint byte write failure");
            }
            return delegate.saveCheckpointBytesWithMetadata(data, meta);
        }

        private boolean shouldFailCheckpointWrite() {
            if (failOnCheckpointWriteNumber != null && checkpointWriteCount == failOnCheckpointWriteNumber) {
                failOnCheckpointWriteNumber = null;
                return true;
            }
            return false;
        }

        @Override
        public Optional<TaskState> loadCheckpoint(String checkpointId) {
            if (failCheckpointLoads) {
                throw new IllegalStateException("simulated checkpoint load failure");
            }
            return delegate.loadCheckpoint(checkpointId);
        }

        @Override
        public void deleteCheckpoint(String checkpointId) {
            delegate.deleteCheckpoint(checkpointId);
        }

        @Override
        public void archiveFragment(MemoryFragment fragment) {
            delegate.archiveFragment(fragment);
        }

        @Override
        public Optional<MemoryFragment> retrieveFragment(String id) {
            return delegate.retrieveFragment(id);
        }

        @Override
        public void putBytes(String key, byte[] data) {
            delegate.putBytes(key, data);
        }

        @Override
        public byte[] getBytes(String key) {
            return delegate.getBytes(key);
        }

        @Override
        public List<CheckpointMetadata> listCheckpointMetadata(String taskId) {
            if (failMetadataReads) {
                throw new IllegalStateException("simulated metadata load failure");
            }
            return delegate.listCheckpointMetadata(taskId);
        }

        @Override
        public java.util.Set<String> listTaskIdsWithCheckpoints() {
            return delegate.listTaskIdsWithCheckpoints();
        }
    }

    static class FailingActionLogWriter extends ActionLogWriter {
        private volatile boolean failNextClose;

        FailingActionLogWriter(String walDirPath) {
            super(walDirPath);
        }

        void failNextClose() {
            this.failNextClose = true;
        }

        @Override
        public void close(String taskId) {
            if (failNextClose) {
                failNextClose = false;
                throw new IllegalStateException("WAL close failed for task " + taskId);
            }
            super.close(taskId);
        }
    }
}
