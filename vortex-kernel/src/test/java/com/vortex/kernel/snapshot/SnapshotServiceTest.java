package com.vortex.kernel.snapshot;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.model.*;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.storage.api.L3ColdStore;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

        TaskState task = service.createTask("retention test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "n1");
        service.checkpoint(task.getTaskId());

        service.appendNode(task.getTaskId(), "THOUGHT", "n2");
        service.checkpoint(task.getTaskId());

        service.appendNode(task.getTaskId(), "THOUGHT", "n3");
        service.checkpoint(task.getTaskId());

        List<CheckpointMetadata> listedByService = service.listCheckpoints(task.getTaskId());
        List<CheckpointMetadata> listedByStore = fakeL3.listCheckpointMetadata(task.getTaskId());

        assertThat(listedByService).hasSize(3);
        assertThat(listedByStore).hasSize(3);
        assertThat(listedByService)
                .extracting(CheckpointMetadata::getCheckpointId)
                .containsExactlyElementsOf(listedByStore.stream()
                        .map(CheckpointMetadata::getCheckpointId)
                        .toList());

        TaskState recovered = service.recover(task.getTaskId(), listedByService.getLast().getCheckpointId());
        assertThat(recovered.getGraph().nodeCount()).isEqualTo(3);
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
        assertThat(service.getTask(task.getTaskId())).isEmpty();
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
    void createBranch_createsAndListsBranch() {
        TaskState task = service.createTask("branch test", "ns");
        DagNode n1 = service.appendNode(task.getTaskId(), "THOUGHT", "fork point");

        TaskBranch branch = service.createBranch(task.getTaskId(), "alt-plan", n1.getNodeId());
        assertThat(branch.getBranchName()).isEqualTo("alt-plan");
        assertThat(branch.getStatus()).isEqualTo(TaskBranch.BranchStatus.ACTIVE);
        assertThat(service.listBranches(task.getTaskId())).hasSize(1);
    }

    @Test
    void onTaskEvicted_createsEmergencyCheckpointWithoutReentrantLookup() throws Exception {
        TaskState task = service.createTask("eviction test", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "pending checkpoint");

        Method onTaskEvicted = SnapshotService.class.getDeclaredMethod(
                "onTaskEvicted", String.class, TaskState.class, RemovalCause.class);
        onTaskEvicted.setAccessible(true);
        onTaskEvicted.invoke(service, task.getTaskId(), task, RemovalCause.SIZE);

        assertThat(task.getLatestCheckpointId()).isNotNull();
        assertThat(service.listCheckpoints(task.getTaskId()))
                .extracting(CheckpointMetadata::getCheckpointId)
                .contains(task.getLatestCheckpointId());
    }

    private SnapshotService newService(FakeL3ColdStore store) {
        return newService(store, 10, 20);
    }

    private SnapshotService newService(FakeL3ColdStore store, int maxDeltasBeforeFull, int maxPerTask) {
        String walDir = tempDir.resolve("wal").toString();
        walWriter = new ActionLogWriter(walDir);
        walReader = new ActionLogReader(walDir);
        walTruncator = new ActionLogTruncator(walReader, walDir);
        dirtySetTracker = new DirtySetTracker();
        checkpointManager = new IncrementalCheckpointManager(store, dirtySetTracker, maxDeltasBeforeFull);
        scheduler = new CheckpointScheduler(50, 60000, false);
        conflictDetector = new BranchMergeConflictDetector();
        branchManager = new BranchManager(10, conflictDetector);
        dotExporter = new DotGraphExporter();
        meterRegistry = new SimpleMeterRegistry();
        checkpointRecoveryMetrics = new CheckpointRecoveryMetrics(meterRegistry);

        ApplicationEventPublisher eventPublisher = event -> {};
        SnapshotService snapshotService = new SnapshotService(
                store, walWriter, walReader, walTruncator,
                checkpointManager, new CheckpointLifecycleManager(store, maxPerTask, 7, 48),
                scheduler, dirtySetTracker, branchManager, dotExporter, eventPublisher, checkpointRecoveryMetrics);
        snapshotService.rebuildCheckpointIndex();
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
}
