package com.vortex.kernel.snapshot;

import com.vortex.common.model.*;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.storage.api.L3ColdStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link TaskLifecycleManager}.
 *
 * <p>Pure-unit tests cover cache operations, task creation, listing, checkpoint-index
 * maintenance, cache invalidation, and pagination boundary logic.</p>
 *
 * <p>Lifecycle-transition tests (completeTask / failTask / idempotence) use a
 * fully-wired {@link SnapshotService} facade to satisfy the checkpoint dependency.</p>
 */
class TaskLifecycleManagerTest {

    @TempDir
    Path tempDir;

    private TaskLifecycleManager tlm;
    private FakeL3ColdStore store;
    private ActionLogWriter walWriter;
    private ActionLogReader walReader;
    private ActionLogTruncator walTruncator;
    private IncrementalCheckpointManager checkpointManager;
    private CheckpointLifecycleManager lifecycleManager;
    private CheckpointScheduler scheduler;
    private DirtySetTracker dirtySetTracker;
    private MemorySloTracker memorySloTracker;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        store = new FakeL3ColdStore();
        String walDirPath = tempDir.resolve("wal").toString();
        walWriter = new ActionLogWriter(walDirPath);
        walReader = new ActionLogReader(walDirPath);
        walTruncator = new ActionLogTruncator(walWriter, walReader, walDirPath);
        dirtySetTracker = new DirtySetTracker();
        checkpointManager = new IncrementalCheckpointManager(store, dirtySetTracker, 10);
        lifecycleManager = new CheckpointLifecycleManager(store, 20, 0, 48);
        scheduler = new CheckpointScheduler(50, 60000, false);
        meterRegistry = new SimpleMeterRegistry();
        memorySloTracker = new MemorySloTracker(meterRegistry);

        tlm = new TaskLifecycleManager(
                store, checkpointManager, lifecycleManager,
                walWriter, walReader, walTruncator,
                scheduler, dirtySetTracker, memorySloTracker,
                new TaskFinalizationMetrics(meterRegistry),
                null, null);
    }

    private SnapshotService createSnapshotService() {
        ApplicationEventPublisher eventPublisher = event -> {};
        BranchMergeConflictDetector conflictDetector = new BranchMergeConflictDetector();
        BranchManager branchManager = new BranchManager(10, conflictDetector);
        DotGraphExporter dotExporter = new DotGraphExporter();
        CheckpointRecoveryMetrics checkpointRecoveryMetrics = new CheckpointRecoveryMetrics(meterRegistry);

        DagMutationService dagMutationSvc = new DagMutationService(
                walWriter, dirtySetTracker, scheduler, eventPublisher, branchManager, tlm);
        RecoveryEngine recoveryEng = new RecoveryEngine(
                walReader, walWriter, checkpointManager, checkpointRecoveryMetrics, memorySloTracker,
                branchManager, scheduler, tlm);

        SnapshotService snapshotService = new SnapshotService(
                tlm, dagMutationSvc, recoveryEng,
                branchManager, dotExporter, walWriter, walTruncator,
                checkpointManager, lifecycleManager, scheduler, checkpointRecoveryMetrics, memorySloTracker);
        tlm.setSnapshotService(snapshotService);
        tlm.setRecoveryEngine(recoveryEng);
        return snapshotService;
    }

    // ========================================================================
    // createTask
    // ========================================================================

    @Test
    void createTask_generatesUniqueId_and_statusRunning() {
        TaskState task = tlm.createTask("test description", "test-namespace");

        assertThat(task.getTaskId()).isNotNull();
        assertThat(task.getTaskId()).isNotEmpty();
        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.RUNNING);
        assertThat(task.getDescription()).isEqualTo("test description");
        assertThat(task.getNamespace()).isEqualTo("test-namespace");
        assertThat(task.getGraph()).isNotNull();
        assertThat(task.getGraph().nodeCount()).isZero();
    }

    @Test
    void createTask_generatesDistinctIds() {
        TaskState task1 = tlm.createTask("first", "ns");
        TaskState task2 = tlm.createTask("second", "ns");

        assertThat(task1.getTaskId()).isNotEqualTo(task2.getTaskId());
    }

    @Test
    void createTask_registersWithScheduler() throws Exception {
        TaskState task = tlm.createTask("scheduled task", "ns");

        java.lang.reflect.Field countersField = CheckpointScheduler.class.getDeclaredField("actionCounters");
        countersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, AtomicLong> counters =
                (ConcurrentHashMap<String, AtomicLong>) countersField.get(scheduler);

        assertThat(counters).containsKey(task.getTaskId());
    }

    // ========================================================================
    // getTask / requireTask
    // ========================================================================

    @Test
    void getTask_returnsCachedTask() {
        TaskState created = tlm.createTask("cached task", "ns");

        Optional<TaskState> found = tlm.getTask(created.getTaskId());

        assertThat(found).isPresent();
        assertThat(found.get().getTaskId()).isEqualTo(created.getTaskId());
    }

    @Test
    void getTask_returnsEmptyForUnknownTask() {
        Optional<TaskState> found = tlm.getTask("nonexistent-task-id");

        assertThat(found).isEmpty();
    }

    @Test
    void getTask_returnsEmptyForEvictedTaskWithoutCheckpoint() {
        TaskState task = tlm.createTask("evicted no checkpoint", "ns");
        tlm.evictFromCacheForTest(task.getTaskId());

        Optional<TaskState> found = tlm.getTask(task.getTaskId());

        assertThat(found).isEmpty();
    }

    @Test
    void requireTask_throwsForUnknownTask() {
        assertThatThrownBy(() -> tlm.requireTask("unknown-task"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task not found");
    }

    // ========================================================================
    // listActiveTasks
    // ========================================================================

    @Test
    void listActiveTasks_returnsAllCachedTasks() {
        tlm.createTask("task A", "ns");
        tlm.createTask("task B", "ns");
        tlm.createTask("task C", "ns");

        List<TaskState> active = tlm.listActiveTasks();

        assertThat(active).hasSize(3);
        assertThat(active).extracting(TaskState::getDescription)
                .containsExactlyInAnyOrder("task A", "task B", "task C");
    }

    @Test
    void listActiveTasks_returnsEmptyWhenNoTasks() {
        List<TaskState> active = tlm.listActiveTasks();

        assertThat(active).isEmpty();
    }

    @Test
    void listActiveTasks_paginatesInReverseCreationOrder() {
        TaskState oldest = tlm.createTask("oldest task", "ns");
        TaskState middle = tlm.createTask("middle task", "ns");
        TaskState newest = tlm.createTask("newest task", "ns");

        oldest.setCreatedAt(Instant.parse("2026-05-25T00:00:00Z"));
        middle.setCreatedAt(Instant.parse("2026-05-25T00:00:05Z"));
        newest.setCreatedAt(Instant.parse("2026-05-25T00:00:10Z"));
        tlm.putTask(oldest.getTaskId(), oldest);
        tlm.putTask(middle.getTaskId(), middle);
        tlm.putTask(newest.getTaskId(), newest);

        TaskLifecycleManager.TaskPage page0 = tlm.listActiveTasks(0, 2);

        assertThat(page0.items()).hasSize(2);
        assertThat(page0.items()).extracting(TaskState::getTaskId)
                .containsExactly(newest.getTaskId(), middle.getTaskId());
        assertThat(page0.total()).isEqualTo(3);
        assertThat(page0.hasNext()).isTrue();

        TaskLifecycleManager.TaskPage page1 = tlm.listActiveTasks(1, 2);

        assertThat(page1.items()).hasSize(1);
        assertThat(page1.items()).extracting(TaskState::getTaskId)
                .containsExactly(oldest.getTaskId());
        assertThat(page1.total()).isEqualTo(3);
        assertThat(page1.hasNext()).isFalse();
    }

    @Test
    void listActiveTasks_onlyRecoversTasksNeededForRequestedPage() {
        SnapshotService snapshotService = createSnapshotService();
        TaskState first = snapshotService.createTask("first", "ns");
        TaskState second = snapshotService.createTask("second", "ns");
        first.setCreatedAt(Instant.parse("2026-05-25T00:00:00Z"));
        second.setCreatedAt(Instant.parse("2026-05-25T00:00:10Z"));
        tlm.putTask(first.getTaskId(), first);
        tlm.putTask(second.getTaskId(), second);
        snapshotService.appendNode(first.getTaskId(), "THOUGHT", "persist first");
        snapshotService.appendNode(second.getTaskId(), "THOUGHT", "persist second");
        snapshotService.checkpoint(first.getTaskId());
        snapshotService.checkpoint(second.getTaskId());

        tlm.evictFromCacheForTest(first.getTaskId());
        tlm.evictFromCacheForTest(second.getTaskId());

        TaskLifecycleManager.TaskPage page = tlm.listActiveTasks(0, 1);

        assertThat(page.items()).extracting(TaskState::getTaskId).containsExactly(second.getTaskId());
        assertThat(tlm.getCachedTask(second.getTaskId())).isPresent();
        assertThat(tlm.getCachedTask(first.getTaskId())).isEmpty();
    }

    @Test
    void listActiveTasks_pageOutOfRangeReturnsEmptyList() {
        tlm.createTask("single task", "ns");

        TaskLifecycleManager.TaskPage page = tlm.listActiveTasks(5, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void listActiveTasks_rejectsNegativePage() {
        assertThatThrownBy(() -> tlm.listActiveTasks(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page must be >= 0");
    }

    @Test
    void listActiveTasks_rejectsZeroSize() {
        assertThatThrownBy(() -> tlm.listActiveTasks(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between 1 and 200");
    }

    @Test
    void listActiveTasks_rejectsSizeAbove200() {
        assertThatThrownBy(() -> tlm.listActiveTasks(0, 201))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between 1 and 200");
    }

    @Test
    void listActiveTasks_acceptsSizeAtBoundary200() {
        TaskLifecycleManager.TaskPage page = tlm.listActiveTasks(0, 200);

        assertThat(page.items()).isEmpty();
        assertThat(page.size()).isEqualTo(200);
    }

    // ========================================================================
    // Internal cache operations
    // ========================================================================

    @Test
    void putTask_and_getCachedTask_workTogether() {
        String taskId = "internal-task-id";
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .description("internal cache task")
                .namespace("internal")
                .graph(new DagGraph())
                .build();

        tlm.putTask(taskId, state);

        Optional<TaskState> found = tlm.getCachedTask(taskId);
        assertThat(found).isPresent();
        assertThat(found.get().getTaskId()).isEqualTo(taskId);
    }

    @Test
    void putTask_replacingRunningTaskDoesNotTriggerEvictionCheckpoint() {
        SnapshotService snapshotService = createSnapshotService();
        TaskState original = snapshotService.createTask("replace me", "ns");
        snapshotService.appendNode(original.getTaskId(), "THOUGHT", "before checkpoint");
        snapshotService.checkpoint(original.getTaskId());
        int checkpointsBefore = snapshotService.listCheckpoints(original.getTaskId()).size();

        TaskState replacement = snapshotService.recover(original.getTaskId(), original.getLatestCheckpointId());
        tlm.putTask(original.getTaskId(), replacement);

        assertThat(snapshotService.listCheckpoints(original.getTaskId()).size()).isEqualTo(checkpointsBefore);
    }

    @Test
    void getCachedTask_returnsEmptyForNonExistentTask() {
        Optional<TaskState> found = tlm.getCachedTask("no-such-task");

        assertThat(found).isEmpty();
    }

    // ========================================================================
    // Checkpoint index
    // ========================================================================

    @Test
    void putLatestCheckpointId_and_getLatestCheckpointId_workTogether() {
        String taskId = "task-with-checkpoint";
        String checkpointId = "cp-uuid-12345";

        tlm.putLatestCheckpointId(taskId, checkpointId);

        assertThat(tlm.getLatestCheckpointId(taskId)).isEqualTo(checkpointId);
    }

    @Test
    void getLatestCheckpointId_returnsNullForUnknownTask() {
        assertThat(tlm.getLatestCheckpointId("unknown-task")).isNull();
    }

    @Test
    void putLatestCheckpointId_overwritesExistingEntry() {
        String taskId = "task-overwrite";
        tlm.putLatestCheckpointId(taskId, "first-cp");

        tlm.putLatestCheckpointId(taskId, "second-cp");

        assertThat(tlm.getLatestCheckpointId(taskId)).isEqualTo("second-cp");
    }

    // ========================================================================
    // Lifecycle transitions (needs SnapshotService facade)
    // ========================================================================

    @Test
    void completeTask_persistsCompletedStateButKeepsItOutOfActiveListings() {
        SnapshotService snapshotService = createSnapshotService();

        TaskState task = snapshotService.createTask("completion test", "ns");
        String taskId = task.getTaskId();
        snapshotService.appendNode(taskId, "THOUGHT", "done");

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.RUNNING);

        tlm.completeTask(taskId);

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(task.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
        assertThat(tlm.getCachedTask(taskId)).isEmpty();
        assertThat(tlm.getTask(taskId)).get()
                .satisfies(recovered -> {
                    assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
                    assertThat(recovered.getFinalizationStatus())
                            .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
                });
        assertThat(tlm.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(taskId);
    }

    @Test
    void failTask_persistsFailedStateButKeepsItOutOfActiveListings() {
        SnapshotService snapshotService = createSnapshotService();

        TaskState task = snapshotService.createTask("failure test", "ns");
        String taskId = task.getTaskId();
        snapshotService.appendNode(taskId, "THOUGHT", "boom");

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.RUNNING);

        tlm.failTask(taskId);

        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
        assertThat(task.getFinalizationStatus()).isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
        assertThat(tlm.getCachedTask(taskId)).isEmpty();
        assertThat(tlm.getTask(taskId)).get()
                .satisfies(recovered -> {
                    assertThat(recovered.getStatus()).isEqualTo(TaskState.TaskStatus.FAILED);
                    assertThat(recovered.getFinalizationStatus())
                            .isEqualTo(TaskState.TaskFinalizationStatus.FINALIZED);
                });
        assertThat(tlm.listActiveTasks(0, 10).items())
                .extracting(TaskState::getTaskId)
                .doesNotContain(taskId);
    }

    @Test
    void attachRecoveredTask_preservesCheckpointTimeBaselineForScheduler() throws Exception {
        SnapshotService snapshotService = createSnapshotService();
        TaskState recovered = TaskState.builder()
                .taskId("recovered-task")
                .description("recovered")
                .namespace("ns")
                .graph(new DagGraph())
                .status(TaskState.TaskStatus.RUNNING)
                .lastCheckpointAt(Instant.parse("2026-05-26T10:15:30Z"))
                .latestCheckpointId("cp-1")
                .build();

        tlm.setSnapshotService(snapshotService);
        tlm.attachRecoveredTask(recovered);

        assertThat(lastCheckpointTimes()).containsEntry(
                recovered.getTaskId(),
                recovered.getLastCheckpointAt().toEpochMilli());
    }

    @Test
    void attachRecoveredTask_unregistersTerminalTaskFromScheduler() {
        SnapshotService snapshotService = createSnapshotService();
        TaskState recovered = TaskState.builder()
                .taskId("terminal-task")
                .description("terminal")
                .namespace("ns")
                .graph(new DagGraph())
                .status(TaskState.TaskStatus.COMPLETED)
                .finalizationStatus(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION)
                .latestCheckpointId("cp-terminal")
                .build();

        tlm.setSnapshotService(snapshotService);
        tlm.attachRecoveredTask(recovered);

        assertThat(isTaskRegistered("terminal-task")).isFalse();
        assertThat(tlm.getCachedTask("terminal-task")).containsSame(recovered);
    }

    @Test
    void isTerminalStatus_returnsTrueForCompletedAndFailed() {
        SnapshotService snapshotService = createSnapshotService();

        TaskState task = snapshotService.createTask("terminal check", "ns");
        String taskId = task.getTaskId();

        tlm.completeTask(taskId);
        assertThat(task.getStatus()).isEqualTo(TaskState.TaskStatus.COMPLETED);
        assertThat(tlm.getCachedTask(taskId)).isEmpty();
        assertThat(tlm.getTask(taskId)).isPresent();
    }

    // ========================================================================
    // Cache eviction / checkpoint guard
    // ========================================================================

    @Test
    void evictFromCacheForTest_removesFromCache() {
        TaskState task = tlm.createTask("evict me", "ns");
        String taskId = task.getTaskId();

        assertThat(tlm.getCachedTask(taskId)).isPresent();

        tlm.evictFromCacheForTest(taskId);

        assertThat(tlm.getCachedTask(taskId)).isEmpty();
    }

    @Test
    void isTaskLoadedForCheckpoint_returnsTrueForCachedTask() {
        TaskState task = tlm.createTask("checkpoint guard", "ns");

        assertThat(tlm.isTaskLoadedForCheckpoint(task.getTaskId())).isTrue();
    }

    @Test
    void isTaskLoadedForCheckpoint_returnsFalseForEvictedTask() {
        TaskState task = tlm.createTask("to be evicted", "ns");
        tlm.evictFromCacheForTest(task.getTaskId());

        assertThat(tlm.isTaskLoadedForCheckpoint(task.getTaskId())).isFalse();
    }

    @Test
    void isTaskLoadedForCheckpoint_returnsFalseForUnknownTask() {
        assertThat(tlm.isTaskLoadedForCheckpoint("nonexistent")).isFalse();
    }

    // ========================================================================
    // TaskPage record
    // ========================================================================

    @Test
    void taskPage_hasNext_whenMoreItemsExist() {
        TaskLifecycleManager.TaskPage page = new TaskLifecycleManager.TaskPage(
                List.of(), 0, 10, 25);

        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void taskPage_hasNext_whenNoMoreItemsExist() {
        TaskLifecycleManager.TaskPage page = new TaskLifecycleManager.TaskPage(
                List.of(), 1, 10, 15);

        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void taskPage_hasNext_atExactBoundary() {
        TaskLifecycleManager.TaskPage page = new TaskLifecycleManager.TaskPage(
                List.of(), 1, 10, 20);

        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void taskPage_itemsAreImmutable() {
        TaskState task = TaskState.builder()
                .taskId("page-task")
                .description("pagination")
                .graph(new DagGraph())
                .build();

        TaskLifecycleManager.TaskPage page = new TaskLifecycleManager.TaskPage(
                List.of(task), 0, 10, 1);

        assertThatThrownBy(() -> page.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================================================================
    // Edge case: null createdAt
    // ========================================================================

    @Test
    void listActiveTasks_handlesNullCreatedAt() {
        TaskState task = tlm.createTask("null ts", "ns");
        task.setCreatedAt(null);

        TaskLifecycleManager.TaskPage page = tlm.listActiveTasks(0, 10);

        assertThat(page.items()).extracting(TaskState::getTaskId)
                .contains(task.getTaskId());
        assertThat(page.total()).isEqualTo(1);
    }

    // ========================================================================
    // Fake L3 for testing
    // ========================================================================

    static class FakeL3ColdStore implements L3ColdStore {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CheckpointMetadata> metadata = new ConcurrentHashMap<>();
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
                    : UUID.randomUUID().toString();
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
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> lastCheckpointTimes() throws Exception {
        java.lang.reflect.Field field = CheckpointScheduler.class.getDeclaredField("lastCheckpointTimes");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(scheduler);
    }

    @SuppressWarnings("unchecked")
    private boolean isTaskRegistered(String taskId) {
        try {
            java.lang.reflect.Field field = CheckpointScheduler.class.getDeclaredField("taskServices");
            field.setAccessible(true);
            Map<String, SnapshotService> services = (Map<String, SnapshotService>) field.get(scheduler);
            return services.containsKey(taskId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inspect scheduler registration", e);
        }
    }
}
