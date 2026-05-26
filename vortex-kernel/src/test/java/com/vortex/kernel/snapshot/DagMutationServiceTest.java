package com.vortex.kernel.snapshot;

import com.vortex.common.model.ActionLogEntry;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.kernel.paging.DagChangeEvent;
import com.vortex.storage.api.L3ColdStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DagMutationService} covering all DAG mutation operations
 * with WAL-before-state pattern verification.
 */
class DagMutationServiceTest {

    @TempDir
    Path tempDir;

    private DagMutationService dagMutationService;
    private TaskLifecycleManager taskLifecycleManager;
    private ActionLogWriter walWriter;
    private ActionLogReader walReader;
    private DirtySetTracker dirtySetTracker;
    private CheckpointScheduler scheduler;
    private List<Object> publishedEvents;
    private ApplicationEventPublisher eventPublisher;
    private BranchManager branchManager;
    private FakeL3ColdStore fakeL3;

    @BeforeEach
    void setUp() {
        fakeL3 = new FakeL3ColdStore();
        String walDir = tempDir.resolve("wal").toString();
        walWriter = new ActionLogWriter(walDir);
        walReader = new ActionLogReader(walDir);
        ActionLogTruncator walTruncator = new ActionLogTruncator(walWriter, walReader, walDir);
        dirtySetTracker = new DirtySetTracker();
        scheduler = new CheckpointScheduler(50, 60000, false);
        publishedEvents = new ArrayList<>();
        eventPublisher = publishedEvents::add;
        BranchMergeConflictDetector conflictDetector = new BranchMergeConflictDetector();
        branchManager = new BranchManager(10, conflictDetector);

        IncrementalCheckpointManager checkpointManager =
                new IncrementalCheckpointManager(fakeL3, dirtySetTracker, 10);
        CheckpointLifecycleManager lifecycleManager =
                new CheckpointLifecycleManager(fakeL3, 20, 7, 48);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MemorySloTracker memorySloTracker = new MemorySloTracker(meterRegistry);
        memorySloTracker.bind();

        taskLifecycleManager = new TaskLifecycleManager(
                fakeL3, checkpointManager, lifecycleManager,
                walWriter, walReader, walTruncator,
                scheduler, dirtySetTracker, memorySloTracker,
                new TaskFinalizationMetrics(meterRegistry),
                null, null);

        dagMutationService = new DagMutationService(
                walWriter, dirtySetTracker, scheduler,
                eventPublisher, branchManager, taskLifecycleManager);
    }

    // ========================================================================
    // appendNode tests
    // ========================================================================

    @Test
    void appendNode_addsNodeToGraph() {
        TaskState task = taskLifecycleManager.createTask("basic node creation", "ns-1");

        DagNode node = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "step 1");

        assertThat(task.getGraph().nodeCount()).isEqualTo(1);
        assertThat(node.getType()).isEqualTo(DagNode.NodeType.THOUGHT);
        assertThat(node.getContent()).isEqualTo("step 1");
        assertThat(node.getStatus()).isEqualTo(DagNode.NodeStatus.PENDING);
        assertThat(node.getNodeId()).isNotEmpty();

        assertThat(task.getGraph().getNode(node.getNodeId())).isPresent();

        List<ActionLogEntry> entries = walReader.readAll(task.getTaskId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getOperation()).isEqualTo(ActionLogEntry.OperationType.APPEND_NODE);
        assertThat(entries.get(0).getSequenceNumber()).isEqualTo(1);
    }

    @Test
    void appendNode_setsCurrentNodeId() {
        TaskState task = taskLifecycleManager.createTask("cursor tracking", "ns-1");

        DagNode node1 = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "first");
        assertThat(task.getCurrentNodeId()).isEqualTo(node1.getNodeId());

        DagNode node2 = dagMutationService.appendNode(task.getTaskId(), "ACTION", "second");
        assertThat(task.getCurrentNodeId()).isEqualTo(node2.getNodeId());
        assertThat(task.getCurrentNodeId()).isNotEqualTo(node1.getNodeId());
    }

    @Test
    void appendNode_publishesNodeAppendedEvent() {
        TaskState task = taskLifecycleManager.createTask("event publication", "ns-1");
        publishedEvents.clear();

        DagNode node = dagMutationService.appendNode(task.getTaskId(), "ACTION", "fire event");

        assertThat(publishedEvents).hasSize(1);
        assertThat(publishedEvents.get(0)).isInstanceOf(DagChangeEvent.NodeAppended.class);
        DagChangeEvent.NodeAppended event = (DagChangeEvent.NodeAppended) publishedEvents.get(0);
        assertThat(event.taskId()).isEqualTo(task.getTaskId());
        assertThat(event.nodeId()).isEqualTo(node.getNodeId());
        assertThat(event.type()).isEqualTo("ACTION");
    }

    @Test
    void appendNode_failsForInvalidType() {
        TaskState task = taskLifecycleManager.createTask("invalid node type", "ns-1");

        assertThatThrownBy(() -> dagMutationService.appendNode(task.getTaskId(), "NOT_A_REAL_TYPE", "bad node"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(task.getGraph().nodeCount()).isZero();
    }

    // ========================================================================
    // appendNodeWithTarget tests
    // ========================================================================

    @Test
    void appendNodeWithTarget_createsEdgeBetweenNodes() {
        TaskState task = taskLifecycleManager.createTask("edge creation", "ns-1");
        DagNode n1 = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "plan");

        DagNode n2 = dagMutationService.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "execute",
                n1.getNodeId(), DagEdge.EdgeType.CONTROL_DEP);

        assertThat(task.getGraph().getEdges()).hasSize(1);
        DagEdge edge = task.getGraph().getEdges().get(0);
        assertThat(edge.getSourceNodeId()).isEqualTo(n1.getNodeId());
        assertThat(edge.getTargetNodeId()).isEqualTo(n2.getNodeId());
        assertThat(edge.getDependencyType()).isEqualTo(DagEdge.EdgeType.CONTROL_DEP);
        assertThat(task.getGraph().areConnected(n1.getNodeId(), n2.getNodeId())).isTrue();
    }

    @Test
    void appendNodeWithTarget_failsForMissingTarget() {
        TaskState task = taskLifecycleManager.createTask("missing target", "ns-1");

        assertThatThrownBy(() -> dagMutationService.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "bad edge",
                "non-existent-node", DagEdge.EdgeType.CONTROL_DEP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Node not found");

        assertThat(task.getGraph().nodeCount()).isZero();
        assertThat(task.getGraph().getEdges()).isEmpty();
    }

    @Test
    void appendNodeWithTarget_publishesBothNodeAppendedAndEdgeAddedEvents() {
        TaskState task = taskLifecycleManager.createTask("combined event test", "ns-1");
        DagNode n1 = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "start");
        publishedEvents.clear();

        DagNode n2 = dagMutationService.appendNodeWithTarget(
                task.getTaskId(), "OBSERVATION", "result",
                n1.getNodeId(), DagEdge.EdgeType.DATA_DEP);

        assertThat(publishedEvents).hasSize(2);
        assertThat(publishedEvents).anyMatch(e -> e instanceof DagChangeEvent.NodeAppended);
        assertThat(publishedEvents).anyMatch(e -> e instanceof DagChangeEvent.EdgeAdded);

        DagChangeEvent.EdgeAdded edgeEvent = publishedEvents.stream()
                .filter(e -> e instanceof DagChangeEvent.EdgeAdded)
                .map(e -> (DagChangeEvent.EdgeAdded) e)
                .findFirst().orElseThrow();
        assertThat(edgeEvent.sourceNodeId()).isEqualTo(n1.getNodeId());
        assertThat(edgeEvent.targetNodeId()).isEqualTo(n2.getNodeId());
        assertThat(edgeEvent.taskId()).isEqualTo(task.getTaskId());
    }

    // ========================================================================
    // addEdge tests
    // ========================================================================

    @Test
    void addEdge_createsValidEdge() {
        TaskState task = taskLifecycleManager.createTask("edge test", "ns-1");
        DagNode n1 = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "source node");
        DagNode n2 = dagMutationService.appendNode(task.getTaskId(), "ACTION", "target node");

        DagEdge edge = dagMutationService.addEdge(
                task.getTaskId(), n1.getNodeId(), n2.getNodeId(),
                DagEdge.EdgeType.CONTROL_DEP, null);

        assertThat(edge).isNotNull();
        assertThat(edge.getSourceNodeId()).isEqualTo(n1.getNodeId());
        assertThat(edge.getTargetNodeId()).isEqualTo(n2.getNodeId());
        assertThat(edge.getDependencyType()).isEqualTo(DagEdge.EdgeType.CONTROL_DEP);
        assertThat(task.getGraph().areConnected(n1.getNodeId(), n2.getNodeId())).isTrue();

        List<ActionLogEntry> entries = walReader.readAll(task.getTaskId());
        assertThat(entries).extracting(ActionLogEntry::getOperation)
                .contains(ActionLogEntry.OperationType.ADD_EDGE);
    }

    @Test
    void addEdge_failsForInvalidEdge_missingTarget() {
        TaskState task = taskLifecycleManager.createTask("invalid edge test", "ns-1");
        DagNode n1 = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "source node");

        assertThatThrownBy(() -> dagMutationService.addEdge(
                task.getTaskId(), n1.getNodeId(), "missing-target",
                DagEdge.EdgeType.CONTROL_DEP, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target node not found");
    }

    // ========================================================================
    // completeNode tests
    // ========================================================================

    @Test
    void completeNode_setsResultAndMarksCompleted() {
        TaskState task = taskLifecycleManager.createTask("node completion", "ns-1");
        DagNode node = dagMutationService.appendNode(task.getTaskId(), "ACTION", "run job");

        DagNode completed = dagMutationService.completeNode(
                task.getTaskId(), node.getNodeId(), "success");

        assertThat(completed.getStatus()).isEqualTo(DagNode.NodeStatus.COMPLETED);
        assertThat(completed.getResult()).isEqualTo("success");
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    void completeNode_publishesNodeCompletedEvent() {
        TaskState task = taskLifecycleManager.createTask("complete event test", "ns-1");
        DagNode node = dagMutationService.appendNode(task.getTaskId(), "ACTION", "do work");
        publishedEvents.clear();

        dagMutationService.completeNode(task.getTaskId(), node.getNodeId(), "result-42");

        assertThat(publishedEvents).hasSize(1);
        assertThat(publishedEvents.get(0)).isInstanceOf(DagChangeEvent.NodeCompleted.class);
        DagChangeEvent.NodeCompleted event = (DagChangeEvent.NodeCompleted) publishedEvents.get(0);
        assertThat(event.taskId()).isEqualTo(task.getTaskId());
        assertThat(event.nodeId()).isEqualTo(node.getNodeId());
        assertThat(event.result()).isEqualTo("result-42");
    }

    @Test
    void completeNode_failsForMissingNode() {
        TaskState task = taskLifecycleManager.createTask("missing complete target", "ns-1");

        assertThatThrownBy(() -> dagMutationService.completeNode(
                task.getTaskId(), "missing-node-id", "result"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Node not found");
    }

    // ========================================================================
    // updateContext tests
    // ========================================================================

    @Test
    void updateContext_addsAndChangesValues() {
        TaskState task = taskLifecycleManager.createTask("context update", "ns-1");

        dagMutationService.updateContext(task.getTaskId(), "key1", "value1");
        assertThat(task.getContext()).containsEntry("key1", "value1");

        dagMutationService.updateContext(task.getTaskId(), "key1", "value2");
        assertThat(task.getContext()).containsEntry("key1", "value2");

        dagMutationService.updateContext(task.getTaskId(), "key2", "another");
        assertThat(task.getContext()).containsEntry("key2", "another");
        assertThat(task.getContext()).hasSize(2);
    }

    @Test
    void updateContext_removesKeyWhenValueIsNull() {
        TaskState task = taskLifecycleManager.createTask("context null removal", "ns-1");
        dagMutationService.updateContext(task.getTaskId(), "tempKey", "tempValue");
        assertThat(task.getContext()).containsKey("tempKey");

        dagMutationService.updateContext(task.getTaskId(), "tempKey", null);
        assertThat(task.getContext()).doesNotContainKey("tempKey");
    }

    // ========================================================================
    // WAL-before-state integrity tests
    // ========================================================================

    @Test
    void invalidOperations_doNotWriteToWal() {
        TaskState task = taskLifecycleManager.createTask("wal integrity guard", "ns-1");
        long seqBefore = walWriter.currentSequenceNumber(task.getTaskId());
        int walEntriesBefore = walReader.readAll(task.getTaskId()).size();

        assertThatThrownBy(() -> dagMutationService.appendNode(
                task.getTaskId(), "NOT_A_TYPE_XYZ", "bad content"))
                .isInstanceOf(IllegalArgumentException.class);

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
        assertThat(task.getGraph().nodeCount()).isZero();

        assertThatThrownBy(() -> dagMutationService.appendNodeWithTarget(
                task.getTaskId(), "ACTION", "bad edge",
                "missing-node-123", DagEdge.EdgeType.CONTROL_DEP))
                .isInstanceOf(IllegalArgumentException.class);

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
        assertThat(task.getGraph().getEdges()).isEmpty();

        assertThatThrownBy(() -> dagMutationService.completeNode(
                task.getTaskId(), "missing-node", "result"))
                .isInstanceOf(IllegalArgumentException.class);

        assertWalUnchanged(task.getTaskId(), seqBefore, walEntriesBefore);
    }

    @Test
    void validMutationAfterInvalidOperation_isNotPoisoned() {
        TaskState task = taskLifecycleManager.createTask("poison test", "ns-1");

        assertThatThrownBy(() -> dagMutationService.appendNode(
                task.getTaskId(), "INVALID", "content"))
                .isInstanceOf(IllegalArgumentException.class);

        DagNode node = dagMutationService.appendNode(task.getTaskId(), "THOUGHT", "valid after invalid");

        assertThat(task.getGraph().nodeCount()).isEqualTo(1);
        assertThat(node.getType()).isEqualTo(DagNode.NodeType.THOUGHT);
        assertThat(node.getContent()).isEqualTo("valid after invalid");

        List<ActionLogEntry> entries = walReader.readAll(task.getTaskId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getOperation()).isEqualTo(ActionLogEntry.OperationType.APPEND_NODE);
    }

    @Test
    void jsonPayload_roundTripsCorrectly() {
        TaskState task = taskLifecycleManager.createTask("payload round-trip", "ns-1");

        DagNode node = dagMutationService.appendNode(
                task.getTaskId(), "ACTION", "JSON payload test content");

        List<ActionLogEntry> entries = walReader.readAll(task.getTaskId());
        assertThat(entries).hasSize(1);

        String payload = entries.get(0).getPayload();
        assertThat(payload).contains("\"nodeId\":\"" + node.getNodeId() + "\"");
        assertThat(payload).contains("\"type\":\"ACTION\"");
        assertThat(payload).contains("\"content\":\"JSON payload test content\"");
        assertThat(payload).startsWith("{");
        assertThat(payload).endsWith("}");
    }

    @Test
    void appendNode_withMissingTask_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> dagMutationService.appendNode("non-existent-task", "THOUGHT", "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task not found");
    }

    @Test
    void completeNode_withMissingTask_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> dagMutationService.completeNode(
                "non-existent-task", "missing-node", "result"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task not found");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void assertWalUnchanged(String taskId, long seqBefore, int walEntriesBefore) {
        assertThat(walWriter.currentSequenceNumber(taskId)).isEqualTo(seqBefore);
        assertThat(walReader.readAll(taskId)).hasSize(walEntriesBefore);
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
                meta.setCreatedAt(java.time.Instant.now());
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
