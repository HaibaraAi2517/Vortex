package com.vortex.kernel.snapshot;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.model.*;
import com.vortex.storage.api.L3ColdStore;
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
        String walDir = tempDir.resolve("wal").toString();
        walWriter = new ActionLogWriter(walDir);
        walReader = new ActionLogReader(walDir);
        walTruncator = new ActionLogTruncator(walReader, walDir);
        dirtySetTracker = new DirtySetTracker();
        checkpointManager = new IncrementalCheckpointManager(store, dirtySetTracker, 10);
        scheduler = new CheckpointScheduler(50, 60000, false);
        conflictDetector = new BranchMergeConflictDetector();
        branchManager = new BranchManager(10, conflictDetector);
        dotExporter = new DotGraphExporter();

        ApplicationEventPublisher eventPublisher = event -> {};
        SnapshotService snapshotService = new SnapshotService(
                store, walWriter, walReader, walTruncator,
                checkpointManager, new CheckpointLifecycleManager(store, 20, 7, 48),
                scheduler, dirtySetTracker, branchManager, dotExporter, eventPublisher);
        snapshotService.rebuildCheckpointIndex();
        return snapshotService;
    }

    // ---- Fake L3 implementation for testing ----

    static class FakeL3ColdStore implements L3ColdStore {
        private final java.util.concurrent.ConcurrentHashMap<String, Object> store = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, CheckpointMetadata> metadata = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void archiveFragment(MemoryFragment fragment) {
            store.put("frag/" + fragment.getId(), fragment);
        }

        @Override
        public Optional<MemoryFragment> retrieveFragment(String id) {
            return Optional.ofNullable((MemoryFragment) store.get("frag/" + id));
        }

        @Override
        public String saveCheckpoint(TaskState state) {
            String cpId = state.getLatestCheckpointId() != null
                    ? state.getLatestCheckpointId()
                    : java.util.UUID.randomUUID().toString();
            state.setLatestCheckpointId(cpId);
            store.put("cp/" + state.getTaskId() + "/" + cpId, state);
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
        @SuppressWarnings("unchecked")
        public Optional<TaskState> loadCheckpoint(String checkpointId) {
            // checkpointId format: taskId/uuid
            String key = "cp/" + checkpointId;
            return Optional.ofNullable((TaskState) store.get(key));
        }

        @Override
        public void deleteCheckpoint(String checkpointId) {
            store.remove("cp/" + checkpointId);
            metadata.remove(checkpointId);
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
