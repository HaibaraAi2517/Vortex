package com.vortex.kernel.snapshot;

import com.vortex.common.model.ActionLogEntry;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.storage.api.L3ColdStore;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Direct unit tests for RecoveryEngine's doRecover logic,
 * including checkpoint resolution, WAL replay, idempotency,
 * scheduler registration, and metrics recording.
 */
class RecoveryEngineTest {

    @TempDir
    Path tempDir;

    private RecoveryEngine recoveryEngine;
    private ActionLogWriter walWriter;
    private ActionLogReader walReader;
    private IncrementalCheckpointManager checkpointManager;
    private CheckpointScheduler scheduler;
    private DirtySetTracker dirtySetTracker;
    private BranchManager branchManager;
    private CheckpointRecoveryMetrics checkpointRecoveryMetrics;
    private MemorySloTracker memorySloTracker;
    private SimpleMeterRegistry meterRegistry;
    private TaskLifecycleManager taskLifecycleManager;
    private DagMutationService dagMutationService;
    private CheckpointLifecycleManager lifecycleManager;
    private FakeL3ColdStore fakeL3;
    private SnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        fakeL3 = new FakeL3ColdStore();
        buildComponents(fakeL3, 10, 20);
    }

    private void buildComponents(FakeL3ColdStore store, int maxDeltasBeforeFull, int maxPerTask) {
        String walDir = tempDir.resolve("wal").toString();
        walWriter = new ActionLogWriter(walDir);
        walReader = new ActionLogReader(walDir);
        ActionLogTruncator walTruncator = new ActionLogTruncator(walWriter, walReader, walDir);
        dirtySetTracker = new DirtySetTracker();
        checkpointManager = new IncrementalCheckpointManager(store, dirtySetTracker, maxDeltasBeforeFull);
        lifecycleManager = new CheckpointLifecycleManager(store, maxPerTask, 0, 48);
        scheduler = new CheckpointScheduler(50, 60000, false);
        BranchMergeConflictDetector conflictDetector = new BranchMergeConflictDetector();
        branchManager = new BranchManager(10, conflictDetector);
        meterRegistry = new SimpleMeterRegistry();
        checkpointRecoveryMetrics = new CheckpointRecoveryMetrics(meterRegistry);
        memorySloTracker = new MemorySloTracker(meterRegistry);
        memorySloTracker.bind();

        taskLifecycleManager = new TaskLifecycleManager(
                store, checkpointManager, lifecycleManager, walWriter, walReader, walTruncator,
                scheduler, dirtySetTracker, memorySloTracker, new TaskFinalizationMetrics(meterRegistry), null, null);

        recoveryEngine = new RecoveryEngine(
                walReader, walWriter, checkpointManager, checkpointRecoveryMetrics, memorySloTracker,
                branchManager, scheduler, taskLifecycleManager);

        dagMutationService = new DagMutationService(
                walWriter, dirtySetTracker, scheduler, event -> {}, branchManager, taskLifecycleManager);

        snapshotService = new SnapshotService(
                taskLifecycleManager, dagMutationService, recoveryEngine,
                branchManager, new DotGraphExporter(), walWriter, walTruncator,
                checkpointManager, lifecycleManager, scheduler, checkpointRecoveryMetrics, memorySloTracker);

        taskLifecycleManager.setSnapshotService(snapshotService);
        taskLifecycleManager.setRecoveryEngine(recoveryEngine);
    }

    // ========================================================================
    // recover with no checkpoint
    // ========================================================================

    @Test
    void recoverWithNoCheckpointThrowsNoCheckpointAvailable() {
        String unknownTaskId = "non-existent-task";

        assertThatThrownBy(() -> recoveryEngine.recover(unknownTaskId, null))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> {
                    CheckpointRecoveryException cex = (CheckpointRecoveryException) ex;
                    assertThat(cex.getReason()).isEqualTo(CheckpointRecoveryFailureReason.NO_CHECKPOINT_AVAILABLE);
                    assertThat(cex.getTaskId()).isEqualTo(unknownTaskId);
                });

        assertThat(counterValue("vortex.checkpoint.recovery.total",
                "outcome", "failure", "mode", "NONE", "reason", "NO_CHECKPOINT_AVAILABLE"))
                .isEqualTo(1.0);
    }

    // ========================================================================
    // recover from full checkpoint
    // ========================================================================

    @Test
    void recoverFromFullCheckpointRestoresGraphNodes() {
        TaskState task = taskLifecycleManager.createTask("full recovery test", "ns");
        DagNode n1 = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "step 1");
        dagMutationService.completeNode(task.getTaskId(), n1.getNodeId(), "done");
        DagNode n2 = dagMutationService.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "step 2", n1.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);

        String checkpointId = createCheckpointViaManager(task);

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getGraph().nodeCount()).isEqualTo(2);
        assertThat(recovered.getGraph().getNode(n1.getNodeId())).isPresent();
        assertThat(recovered.getGraph().getNode(n2.getNodeId())).isPresent();
        assertThat(recovered.getGraph().getEdges()).hasSize(1);
        assertThat(recovered.getLatestCheckpointId()).isEqualTo(checkpointId);
    }

    // ========================================================================
    // recover replays WAL entries after checkpoint
    // ========================================================================

    @Test
    void recoverReplaysWalEntriesAfterCheckpoint() {
        TaskState task = taskLifecycleManager.createTask("wal replay test", "ns");
        DagNode n1 = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);

        DagNode n2 = dagMutationService.appendNode(task.getTaskId(), "ACTION", "after checkpoint");
        dagMutationService.completeNode(task.getTaskId(), n2.getNodeId(), "done");

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getGraph().nodeCount()).isEqualTo(2);
        assertThat(recovered.getGraph().getNode(n1.getNodeId())).isPresent();
        assertThat(recovered.getGraph().getNode(n2.getNodeId())).isPresent();
        assertThat(recovered.getGraph().getNode(n2.getNodeId()).get().getStatus())
                .isEqualTo(DagNode.NodeStatus.COMPLETED);
    }

    // ========================================================================
    // recover sets status to RUNNING
    // ========================================================================

    @Test
    void recoverSetsStatusToRunningAfterRecovery() {
        TaskState task = taskLifecycleManager.createTask("status transition test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.RUNNING);
    }

    // ========================================================================
    // recover records success metric
    // ========================================================================

    @Test
    void recoverRecordsSuccessMetric() {
        TaskState task = taskLifecycleManager.createTask("success metric test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);

        checkpointManager.reloadTask(task.getTaskId());
        recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(counterValue("vortex.checkpoint.recovery.total",
                "outcome", "success", "mode", "FULL"))
                .isEqualTo(1.0);
    }

    @Test
    void recoverPreservesFinalizedStatusFromCheckpoint() {
        TaskState task = taskLifecycleManager.createTask("finalized recovery test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        task.setStatus(TaskState.TaskStatus.COMPLETED);
        task.setFinalizationStatus(TaskState.TaskFinalizationStatus.FINALIZED);

        String checkpointId = createCheckpointViaManager(task);

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(recovered.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
    }

    // ========================================================================
    // recover registers task with scheduler
    // ========================================================================

    @Test
    void recoverRegistersTaskWithScheduler() {
        TaskState task = taskLifecycleManager.createTask("scheduler registration test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);

        scheduler.unregisterTask(task.getTaskId());

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.RUNNING);
        // Verify task is registered by recording an action (no exception = registered)
        scheduler.recordAction(task.getTaskId());
    }

    @Test
    void recoverPreservesCheckpointTimestampAsSchedulerBaseline() throws Exception {
        TaskState task = taskLifecycleManager.createTask("scheduler baseline test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);
        Instant checkpointCreatedAt = checkpointManager.latestCheckpoint(task.getTaskId())
                .orElseThrow()
                .getCreatedAt();

        scheduler.unregisterTask(task.getTaskId());
        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getLastCheckpointAt()).isEqualTo(checkpointCreatedAt);
        assertThat(lastCheckpointTimes()).containsEntry(task.getTaskId(), checkpointCreatedAt.toEpochMilli());
    }

    @Test
    void recoverDoesNotRegisterTerminalTaskWithScheduler() {
        TaskState task = taskLifecycleManager.createTask("terminal recover test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);
        String setStatusPayload = recoveryEngine.jsonPayload(
                "status", "COMPLETED",
                "finalizationStatus", "PENDING_FINALIZATION");
        walWriter.append(task.getTaskId(), ActionLogEntry.OperationType.SET_STATUS, setStatusPayload);
        scheduler.unregisterTask(task.getTaskId());

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(recovered.getFinalizationStatus())
                .isEqualTo(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        assertThat(isTaskRegistered(task.getTaskId())).isFalse();
    }

    // ========================================================================
    // recover: checkpoint resolution from cache
    // ========================================================================

    @Test
    void doRecoverResolvesLatestCheckpointFromCacheWhenNoIdGiven() {
        TaskState task = taskLifecycleManager.createTask("cache resolution test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "step");

        String checkpointId = createCheckpointViaManager(task);

        taskLifecycleManager.putLatestCheckpointId(task.getTaskId(), checkpointId);
        taskLifecycleManager.putTask(task.getTaskId(), task);
        task.setLatestCheckpointId(checkpointId);

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), null);

        assertThat(recovered.getLatestCheckpointId()).isEqualTo(checkpointId);
        assertThat(recovered.getGraph().nodeCount()).isEqualTo(1);
    }

    // ========================================================================
    // recover: checkpoint resolution from task index
    // ========================================================================

    @Test
    void doRecoverResolvesLatestCheckpointFromTaskIndexWhenNoIdGiven() {
        TaskState task = taskLifecycleManager.createTask("index fallback test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "step");

        String checkpointId = createCheckpointViaManager(task);

        taskLifecycleManager.evictFromCacheForTest(task.getTaskId());
        taskLifecycleManager.putLatestCheckpointId(task.getTaskId(), checkpointId);

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), null);

        assertThat(recovered.getLatestCheckpointId()).isEqualTo(checkpointId);
        assertThat(recovered.getGraph().nodeCount()).isEqualTo(1);
    }

    // ========================================================================
    // recover: checkpoint resolution from L3 index
    // ========================================================================

    @Test
    void doRecoverResolvesLatestCheckpointFromL3IndexWhenCacheAndIndexEmpty() {
        TaskState task = taskLifecycleManager.createTask("l3 fallback test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "step");

        String checkpointId = createCheckpointViaManager(task);

        taskLifecycleManager.evictFromCacheForTest(task.getTaskId());

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), null);

        assertThat(recovered.getLatestCheckpointId()).isEqualTo(checkpointId);
        assertThat(recovered.getGraph().nodeCount()).isEqualTo(1);
    }

    // ========================================================================
    // jsonPayload
    // ========================================================================

    @Test
    void jsonPayloadProducesValidJson() {
        String json = recoveryEngine.jsonPayload("key1", "value1", "key2", "value2");

        assertThat(json).isNotNull();
        assertThat(json).contains("\"key1\"");
        assertThat(json).contains("\"value1\"");
        assertThat(json).contains("\"key2\"");
        assertThat(json).contains("\"value2\"");
        assertThat(json).startsWith("{");
        assertThat(json).endsWith("}");
    }

    @Test
    void jsonPayloadRejectsOddArgumentCount() {
        assertThatThrownBy(() -> recoveryEngine.jsonPayload("key1", "value1", "key2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even number");
    }

    @Test
    void jsonPayloadHandlesNullValues() {
        String json = recoveryEngine.jsonPayload("key1", null);

        assertThat(json).contains("\"key1\"");
        assertThat(json).contains("\"\"");
    }

    // ========================================================================
    // recover replays SET_STATUS WAL entry
    // ========================================================================

    @Test
    void recoverReplaysSetStatusWalEntry() {
        TaskState task = taskLifecycleManager.createTask("set status replay test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);

        String setStatusPayload = recoveryEngine.jsonPayload(
                "status", "FAILED",
                "finalizationStatus", "PENDING_FINALIZATION");
        walWriter.append(task.getTaskId(), ActionLogEntry.OperationType.SET_STATUS, setStatusPayload);

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
        assertThat(recovered.getFinalizationStatus())
                .isEqualTo(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
    }

    // ========================================================================
    // recover replays UPDATE_CONTEXT WAL entry
    // ========================================================================

    @Test
    void recoverReplaysUpdateContextWalEntry() {
        TaskState task = taskLifecycleManager.createTask("context replay test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);

        String ctxPayload = recoveryEngine.jsonPayload("key", "env-var", "value", "production");
        walWriter.append(task.getTaskId(), ActionLogEntry.OperationType.UPDATE_CONTEXT, ctxPayload);

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(recovered.getContext()).containsEntry("env-var", "production");
    }

    // ========================================================================
    // recover with completeNode referencing missing node
    // ========================================================================

    @Test
    void recoverWithCompleteNodeReferencingMissingNodeThrowsTypedFailure() {
        TaskState task = taskLifecycleManager.createTask("missing complete target", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);

        String payload = recoveryEngine.jsonPayload("nodeId", "nonexistent-node", "result", "done");
        walWriter.append(task.getTaskId(), ActionLogEntry.OperationType.COMPLETE_NODE, payload);

        checkpointManager.reloadTask(task.getTaskId());
        assertThatThrownBy(() -> recoveryEngine.recover(task.getTaskId(), checkpointId))
                .isInstanceOf(CheckpointRecoveryException.class)
                .satisfies(ex -> {
                    CheckpointRecoveryException cex = (CheckpointRecoveryException) ex;
                    assertThat(cex.getReason()).isEqualTo(CheckpointRecoveryFailureReason.WAL_STATE_APPLY_FAILED);
                    assertThat(cex.getMessage()).contains("nonexistent-node");
                });
    }

    // ========================================================================
    // recovery populates task in cache
    // ========================================================================

    @Test
    void recoverPutsTaskInLifecycleManagerCache() {
        TaskState task = taskLifecycleManager.createTask("cache population test", "ns");
        dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");

        String checkpointId = createCheckpointViaManager(task);

        taskLifecycleManager.evictFromCacheForTest(task.getTaskId());

        checkpointManager.reloadTask(task.getTaskId());
        TaskState recovered = recoveryEngine.recover(task.getTaskId(), checkpointId);

        assertThat(taskLifecycleManager.getCachedTask(task.getTaskId())).isPresent();
        assertThat(taskLifecycleManager.getCachedTask(task.getTaskId()).get())
                .isSameAs(recovered);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String createCheckpointViaManager(TaskState task) {
        return checkpointManager.createCheckpoint(task, task.getWalSequenceNumber()).getCheckpointId();
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

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Long> lastCheckpointTimes() throws Exception {
        java.lang.reflect.Field field = CheckpointScheduler.class.getDeclaredField("lastCheckpointTimes");
        field.setAccessible(true);
        return (java.util.Map<String, Long>) field.get(scheduler);
    }

    @SuppressWarnings("unchecked")
    private boolean isTaskRegistered(String taskId) {
        try {
            java.lang.reflect.Field field = CheckpointScheduler.class.getDeclaredField("taskServices");
            field.setAccessible(true);
            java.util.Map<String, SnapshotService> services =
                    (java.util.Map<String, SnapshotService>) field.get(scheduler);
            return services.containsKey(taskId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect scheduler registration", e);
        }
    }

    // ========================================================================
    // Fake L3 implementation for testing
    // ========================================================================

    static class FakeL3ColdStore implements L3ColdStore {
        private final java.util.concurrent.ConcurrentHashMap<String, byte[]> store =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, CheckpointMetadata> metadata =
                new java.util.concurrent.ConcurrentHashMap<>();
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
                meta.setCreatedAt(Instant.now());
            }
            meta.setL3Key(key);
            metadata.put(meta.getTaskId() + "/" + meta.getCheckpointId(), meta);
            return meta;
        }

        @Override
        public Optional<TaskState> loadCheckpoint(String checkpointId) {
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

        @Override
        public List<CheckpointMetadata> listCheckpointMetadata(String taskId) {
            return metadata.values().stream()
                    .filter(meta -> taskId.equals(meta.getTaskId()))
                    .sorted(Comparator.comparing(CheckpointMetadata::getCreatedAt))
                    .toList();
        }

        @Override
        public Set<String> listTaskIdsWithCheckpoints() {
            return metadata.values().stream()
                    .map(CheckpointMetadata::getTaskId)
                    .collect(Collectors.toSet());
        }
    }
}
