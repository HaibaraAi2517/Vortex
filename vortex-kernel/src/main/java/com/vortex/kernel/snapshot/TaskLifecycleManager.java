package com.vortex.kernel.snapshot;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.TaskState;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.storage.api.L3ColdStore;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Task lifecycle manager extracted from {@link SnapshotService}.
 *
 * Responsible for task CRUD, in-memory caching, lazy-loading from L3,
 * checkpoint-index maintenance, and lifecycle transitions (complete / fail).
 * Delegates recovery to {@link RecoveryEngine} and checkpoint execution to
 * the {@link SnapshotService} facade via {@code @Lazy} injection to break
 * the circular dependency.
 */
@Slf4j
@Component
public class TaskLifecycleManager {
    private static final String ACTIVE_TASK_INDEX_KEY = "system/active-task-index.bin";

    private final L3ColdStore l3;
    private final IncrementalCheckpointManager checkpointManager;
    private final CheckpointLifecycleManager lifecycleManager;
    private final ActionLogWriter walWriter;
    private final ActionLogReader walReader;
    private final ActionLogTruncator walTruncator;
    private final CheckpointScheduler scheduler;
    private final DirtySetTracker dirtySetTracker;
    private final MemorySloTracker memorySloTracker;
    private final TaskFinalizationMetrics taskFinalizationMetrics;
    private SnapshotService snapshotService;
    private RecoveryEngine recoveryEngine;

    /** In-memory registry of active tasks. */
    private final Cache<String, TaskState> activeTasks = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(2))
            .removalListener(this::onTaskEvicted)
            .build();

    /** In-memory registry for terminal tasks waiting for successful final checkpoint finalization. */
    private final ConcurrentHashMap<String, TaskState> pendingFinalizationTasks = new ConcurrentHashMap<>();

    /** In-memory registry for terminal tasks whose final checkpoint succeeded but post-finalization cleanup must be retried. */
    private final ConcurrentHashMap<String, TaskState> pendingFinalizationCleanupTasks = new ConcurrentHashMap<>();

    /** In-memory registry for tasks whose delete intent is durable but physical cleanup must be retried. */
    private final Set<String> pendingDeletionCleanupTasks = ConcurrentHashMap.newKeySet();

    /** Durable latest-checkpoint index rebuilt from L3 on startup. */
    private final ConcurrentHashMap<String, String> latestCheckpointIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskListingEntry> taskListingIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> checkpointLocks = new ConcurrentHashMap<>();
    private final KryoSerializer kryoSerializer = new KryoSerializer();
    private volatile boolean taskListingIndexSnapshotPresent;

    private final ThreadLocal<Set<String>> evictionCheckpointGuard =
            ThreadLocal.withInitial(HashSet::new);

    public TaskLifecycleManager(
            L3ColdStore l3,
            IncrementalCheckpointManager checkpointManager,
            CheckpointLifecycleManager lifecycleManager,
            ActionLogWriter walWriter,
            ActionLogReader walReader,
            ActionLogTruncator walTruncator,
            CheckpointScheduler scheduler,
            DirtySetTracker dirtySetTracker,
            MemorySloTracker memorySloTracker,
            TaskFinalizationMetrics taskFinalizationMetrics,
            @Lazy SnapshotService snapshotService,
            @Lazy RecoveryEngine recoveryEngine) {
        this.l3 = l3;
        this.checkpointManager = checkpointManager;
        this.lifecycleManager = lifecycleManager;
        this.walWriter = walWriter;
        this.walReader = walReader;
        this.walTruncator = walTruncator;
        this.scheduler = scheduler;
        this.dirtySetTracker = dirtySetTracker;
        this.memorySloTracker = memorySloTracker;
        this.taskFinalizationMetrics = taskFinalizationMetrics;
        this.snapshotService = snapshotService;
        this.recoveryEngine = recoveryEngine;
    }

    /**
     * Setters for the {@code @Lazy} dependencies; used to resolve circular
     * dependencies during programmatic construction (e.g., unit tests).
     * In a Spring context the {@code @Lazy} proxies handle this automatically.
     */
    void setSnapshotService(SnapshotService snapshotService) {
        if (this.snapshotService == null) {
            this.snapshotService = snapshotService;
        }
    }

    void setRecoveryEngine(RecoveryEngine recoveryEngine) {
        if (this.recoveryEngine == null) {
            this.recoveryEngine = recoveryEngine;
        }
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    @PostConstruct
    void rebuildCheckpointIndex() {
        loadTaskListingIndex();
        for (String taskId : l3.listTaskIdsWithCheckpoints()) {
            if (isDeleteCommitted(taskId)) {
                pendingDeletionCleanupTasks.add(taskId);
                log.info("Skipped deleted task during checkpoint index rebuild taskId={}", taskId);
                continue;
            }
            checkpointManager.latestCheckpoint(taskId).ifPresent(meta -> {
                latestCheckpointIds.put(taskId, meta.getCheckpointId());
                log.info("Recovered checkpoint index taskId={} checkpointId={}",
                        taskId, meta.getCheckpointId());
            });
        }
    }

    // ========================================================================
    // Task Lifecycle
    // ========================================================================

    /**
     * Create and register a new task.
     */
    public TaskState createTask(String description, String namespace) {
        String taskId = UUID.randomUUID().toString();
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .description(description)
                .namespace(namespace)
                .graph(new com.vortex.common.model.DagGraph())
                .build();
        activeTasks.put(taskId, state);
        recordListingState(state);
        scheduler.registerTask(taskId, snapshotService);

        log.info("Task created taskId={} namespace={}", taskId, namespace);
        return state;
    }

    /**
     * Get the current state of a task. Lazily recovers from L3 if not in memory.
     */
    public Optional<TaskState> getTask(String taskId) {
        if (isDeleteCommitted(taskId)) {
            return Optional.empty();
        }
        TaskState cached = activeTasks.getIfPresent(taskId);
        if (cached != null) {
            return Optional.of(cached);
        }
        TaskState pending = pendingFinalizationTasks.get(taskId);
        if (pending != null) {
            return Optional.of(pending);
        }
        TaskState pendingCleanup = pendingFinalizationCleanupTasks.get(taskId);
        if (pendingCleanup != null) {
            return Optional.of(pendingCleanup);
        }

        String checkpointId = latestCheckpointIds.get(taskId);
        if (checkpointId == null) {
            return Optional.empty();
        }
        TaskState recovered = recoveryEngine.doRecover(taskId, checkpointId);
        attachRecoveredTask(recovered);
        log.info("Lazy-loaded task from L3 taskId={}", taskId);
        return Optional.of(recovered);
    }

    /**
     * Get the current state of a task, throwing if not found.
     */
    public TaskState requireTask(String taskId) {
        return getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    /**
     * List all active tasks currently in the in-memory cache.
     */
    public List<TaskState> listActiveTasks() {
        return activeTasks.asMap().values().stream()
                .filter(this::isActiveTaskForListing)
                .toList();
    }

    /**
     * List active tasks with pagination, including both cached and L3-indexed tasks
     * (lazy-loaded for listing purposes only).
     */
    public TaskPage listActiveTasks(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0 || size > 200) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }

        Map<String, TaskState> cachedTasks = new HashMap<>(activeTasks.asMap());
        pendingFinalizationTasks.forEach(cachedTasks::putIfAbsent);
        pendingFinalizationCleanupTasks.forEach(cachedTasks::putIfAbsent);

        List<TaskListingEntry> orderedEntries = collectVisibleListingEntries(cachedTasks);
        int total = orderedEntries.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);

        List<TaskState> items = orderedEntries.subList(fromIndex, toIndex).stream()
                .map(entry -> cachedTasks.containsKey(entry.getTaskId())
                        ? cachedTasks.get(entry.getTaskId())
                        : loadTaskForListing(entry.getTaskId()))
                .filter(Objects::nonNull)
                .toList();
        return new TaskPage(items, page, size, total);
    }

    /**
     * Mark a task as completed: final checkpoint, WAL close, and cleanup.
     */
    public void completeTask(String taskId) {
        transitionToTerminalState(taskId, TaskState.TaskStatus.COMPLETED);
    }

    /**
     * Mark a task as failed: final checkpoint attempt, WAL close, and cleanup.
     */
    public void failTask(String taskId) {
        transitionToTerminalState(taskId, TaskState.TaskStatus.FAILED);
    }

    /**
     * Hard-delete a task together with its WAL and checkpoints.
     */
    public boolean deleteTask(String taskId) {
        TaskState cached = activeTasks.getIfPresent(taskId);
        List<CheckpointMetadata> checkpoints = checkpointManager.reloadTask(taskId);
        boolean walExists = walReader.exists(taskId);
        boolean indexed = latestCheckpointIds.containsKey(taskId);
        boolean pending = pendingFinalizationTasks.containsKey(taskId);
        boolean pendingCleanup = pendingFinalizationCleanupTasks.containsKey(taskId);
        boolean deleteCommitted = isDeleteCommitted(taskId);
        if (cached == null
                && !pending
                && !pendingCleanup
                && !deleteCommitted
                && checkpoints.isEmpty()
                && !walExists
                && !indexed) {
            return false;
        }

        scheduler.unregisterTask(taskId);
        activeTasks.invalidate(taskId);
        pendingFinalizationTasks.remove(taskId);
        pendingFinalizationCleanupTasks.remove(taskId);
        latestCheckpointIds.remove(taskId);
        removeListingState(taskId);

        if (!deleteCommitted) {
            walWriter.append(taskId, com.vortex.common.model.ActionLogEntry.OperationType.DELETE_TASK,
                    jsonPayload("taskId", taskId));
            log.info("Delete intent recorded taskId={} checkpoints={} hadWal={}",
                    taskId, checkpoints.size(), walExists);
        }

        pendingDeletionCleanupTasks.add(taskId);
        try {
            cleanupDeletedTaskArtifacts(taskId, checkpoints);
        } catch (RuntimeException e) {
            log.warn("Delete cleanup pending taskId={} remainingCheckpoints={} walExists={}",
                    taskId, checkpoints.size(), walReader.exists(taskId), e);
            throw e;
        }

        log.info("Task deleted taskId={} checkpointsRemoved={} hadWal={}",
                taskId, checkpoints.size(), walExists);
        return true;
    }

    /**
     * Check whether a task is currently loaded in the memory cache, so the
     * checkpoint scheduler can decide whether an auto-checkpoint is safe.
     */
    public boolean isTaskLoadedForCheckpoint(String taskId) {
        return activeTasks.getIfPresent(taskId) != null;
    }

    // ========================================================================
    // Package-private (test support)
    // ========================================================================

    /** Test-only: evicts task from cache to verify lazy recovery. */
    void evictFromCacheForTest(String taskId) {
        activeTasks.invalidate(taskId);
    }

    Optional<TaskState> getCachedTask(String taskId) {
        if (pendingDeletionCleanupTasks.contains(taskId)) {
            return Optional.empty();
        }
        TaskState active = activeTasks.getIfPresent(taskId);
        if (active != null) {
            return Optional.of(active);
        }
        TaskState pending = pendingFinalizationTasks.get(taskId);
        if (pending != null) {
            return Optional.of(pending);
        }
        return Optional.ofNullable(pendingFinalizationCleanupTasks.get(taskId));
    }

    String getLatestCheckpointId(String taskId) {
        return latestCheckpointIds.get(taskId);
    }

    void putLatestCheckpointId(String taskId, String checkpointId) {
        if (pendingDeletionCleanupTasks.contains(taskId)) {
            return;
        }
        latestCheckpointIds.put(taskId, checkpointId);
    }

    void putTask(String taskId, TaskState state) {
        if (pendingDeletionCleanupTasks.contains(taskId)) {
            activeTasks.invalidate(taskId);
            pendingFinalizationTasks.remove(taskId);
            pendingFinalizationCleanupTasks.remove(taskId);
            return;
        }
        if (state.getFinalizationStatus() == TaskState.TaskFinalizationStatus.PENDING_FINALIZATION) {
            pendingFinalizationTasks.put(taskId, state);
            pendingFinalizationCleanupTasks.remove(taskId);
            refreshFinalizationMetrics();
            activeTasks.invalidate(taskId);
            removeListingState(taskId);
            return;
        }
        pendingFinalizationTasks.remove(taskId);
        if (isTerminalStatus(state.getStatus())) {
            pendingFinalizationCleanupTasks.put(taskId, state);
            refreshFinalizationMetrics();
            activeTasks.invalidate(taskId);
            removeListingState(taskId);
            return;
        }
        pendingFinalizationCleanupTasks.remove(taskId);
        refreshFinalizationMetrics();
        recordListingState(state);
        activeTasks.put(taskId, state);
    }

    void attachRecoveredTask(TaskState state) {
        if (pendingDeletionCleanupTasks.contains(state.getTaskId())) {
            activeTasks.invalidate(state.getTaskId());
            pendingFinalizationTasks.remove(state.getTaskId());
            pendingFinalizationCleanupTasks.remove(state.getTaskId());
            return;
        }
        activeTasks.invalidate(state.getTaskId());
        pendingFinalizationTasks.remove(state.getTaskId());
        pendingFinalizationCleanupTasks.remove(state.getTaskId());
        if (isTerminalStatus(state.getStatus())) {
            scheduler.unregisterTask(state.getTaskId());
            if (state.getFinalizationStatus() == TaskState.TaskFinalizationStatus.PENDING_FINALIZATION) {
                pendingFinalizationTasks.put(state.getTaskId(), state);
            } else if (walReader.exists(state.getTaskId())) {
                pendingFinalizationCleanupTasks.put(state.getTaskId(), state);
            } else {
                pendingFinalizationCleanupTasks.remove(state.getTaskId());
            }
            refreshFinalizationMetrics();
            removeListingState(state.getTaskId());
            return;
        }
        state.setFinalizationStatus(TaskState.TaskFinalizationStatus.NONE);
        refreshFinalizationMetrics();
        recordListingState(state);
        activeTasks.put(state.getTaskId(), state);
        scheduler.registerTask(state.getTaskId(), snapshotService, checkpointBaselineMillis(state));
    }

    ConcurrentHashMap<String, ReentrantLock> getCheckpointLocks() {
        return checkpointLocks;
    }

    boolean isDeleteCommitted(String taskId) {
        if (pendingDeletionCleanupTasks.contains(taskId)) {
            return true;
        }
        Optional<com.vortex.common.model.ActionLogEntry> lastEntry = walReader.lastEntry(taskId);
        boolean deleted = lastEntry
                .map(entry -> entry.getOperation() == com.vortex.common.model.ActionLogEntry.OperationType.DELETE_TASK)
                .orElse(false);
        if (deleted) {
            pendingDeletionCleanupTasks.add(taskId);
            activeTasks.invalidate(taskId);
            pendingFinalizationTasks.remove(taskId);
            pendingFinalizationCleanupTasks.remove(taskId);
            latestCheckpointIds.remove(taskId);
        }
        return deleted;
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private TaskState loadTaskForListing(String taskId) {
        return getTask(taskId).orElse(null);
    }

    private List<TaskListingEntry> collectVisibleListingEntries(Map<String, TaskState> cachedTasks) {
        Map<String, TaskListingEntry> visible = new LinkedHashMap<>();
        cachedTasks.values().stream()
                .filter(Objects::nonNull)
                .filter(this::isActiveTaskForListing)
                .map(this::toListingEntry)
                .forEach(entry -> visible.put(entry.getTaskId(), entry));

        taskListingIndex.forEach((taskId, entry) -> {
            if (!visible.containsKey(taskId) && !pendingDeletionCleanupTasks.contains(taskId)) {
                visible.put(taskId, entry);
            }
        });
        if (!taskListingIndexSnapshotPresent) {
            latestCheckpointIds.keySet().forEach(taskId -> {
                if (!visible.containsKey(taskId) && !pendingDeletionCleanupTasks.contains(taskId)) {
                    checkpointManager.latestCheckpoint(taskId)
                            .map(meta -> new TaskListingEntry(taskId, null, null, meta.getCreatedAt()))
                            .ifPresent(entry -> visible.put(taskId, entry));
                }
            });
        }

        return visible.values().stream()
                .sorted(Comparator.comparing(
                                TaskListingEntry::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(TaskListingEntry::getTaskId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private void cleanupDeletedTaskArtifacts(String taskId, List<CheckpointMetadata> checkpoints) {
        try {
            for (CheckpointMetadata checkpoint : checkpoints) {
                l3.deleteCheckpoint(checkpoint);
            }
            checkpointManager.removeTask(taskId);
            latestCheckpointIds.remove(taskId);
            removeListingState(taskId);
            walWriter.close(taskId);
            walReader.deleteStrict(taskId);
            pendingDeletionCleanupTasks.remove(taskId);
        } catch (RuntimeException e) {
            checkpointManager.removeTask(taskId);
            latestCheckpointIds.remove(taskId);
            removeListingState(taskId);
            pendingDeletionCleanupTasks.add(taskId);
            throw e;
        }
    }

    private void transitionToTerminalState(String taskId, TaskState.TaskStatus terminalStatus) {
        TaskState state = requireTask(taskId);
        if (isTerminalStatus(state.getStatus()) && state.getFinalizationStatus() == TaskState.TaskFinalizationStatus.FINALIZED) {
            retryFinalizationCleanup(taskId, state);
            return;
        }

        boolean statusAlreadyTerminal = state.getStatus() == terminalStatus;
        if (!statusAlreadyTerminal) {
            createRecoveryAnchorIfNeeded(taskId, state);

            state.setFinalizationStatus(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
            String payload = jsonPayload(
                    "status", terminalStatus.name(),
                    "finalizationStatus", state.getFinalizationStatus().name());
            com.vortex.common.model.ActionLogEntry entry = walWriter.append(
                    taskId,
                    com.vortex.common.model.ActionLogEntry.OperationType.SET_STATUS,
                    payload);
            state.setWalSequenceNumber(entry.getSequenceNumber());
            state.setStatus(terminalStatus);
        } else if (state.getFinalizationStatus() == null || state.getFinalizationStatus() == TaskState.TaskFinalizationStatus.NONE) {
            state.setFinalizationStatus(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        }

        try {
            state.setFinalizationStatus(TaskState.TaskFinalizationStatus.FINALIZED);
            snapshotService.checkpointLoadedTask(taskId, state);
        } catch (RuntimeException e) {
            state.setFinalizationStatus(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
            parkPendingFinalizationTask(taskId, state);
            log.warn("Final checkpoint failed for terminal transition taskId={} status={} checkpointId={}",
                    taskId, terminalStatus, state.getLatestCheckpointId(), e);
            throw e;
        }
        finalizeTerminalTask(taskId, state);

        log.info("Task transitioned to terminal state taskId={} status={} totalNodes={}",
                taskId, terminalStatus, state.getGraph().nodeCount());
    }

    private void createRecoveryAnchorIfNeeded(String taskId, TaskState state) {
        if (state.getLatestCheckpointId() != null) {
            return;
        }
        snapshotService.checkpointLoadedTask(taskId, state);
    }

    private void finalizeTerminalTask(String taskId, TaskState state) {
        state.setFinalizationStatus(TaskState.TaskFinalizationStatus.FINALIZED);
        try {
            walWriter.close(taskId);
            walReader.deleteStrict(taskId);
            completeFinalization(taskId);
        } catch (RuntimeException e) {
            parkPendingFinalizationCleanupTask(taskId, state);
            log.warn("Post-finalization cleanup pending taskId={} status={} checkpointId={}",
                    taskId, state.getStatus(), state.getLatestCheckpointId(), e);
            throw e;
        }
    }

    private void parkPendingFinalizationTask(String taskId, TaskState state) {
        scheduler.unregisterTask(taskId);
        activeTasks.invalidate(taskId);
        pendingFinalizationCleanupTasks.remove(taskId);
        state.setFinalizationStatus(TaskState.TaskFinalizationStatus.PENDING_FINALIZATION);
        pendingFinalizationTasks.put(taskId, state);
        removeListingState(taskId);
        if (taskFinalizationMetrics != null) {
            taskFinalizationMetrics.recordPendingFinalizationEntered();
        }
        refreshFinalizationMetrics();
    }

    private void parkPendingFinalizationCleanupTask(String taskId, TaskState state) {
        scheduler.unregisterTask(taskId);
        activeTasks.invalidate(taskId);
        pendingFinalizationTasks.remove(taskId);
        pendingFinalizationCleanupTasks.put(taskId, state);
        removeListingState(taskId);
        if (taskFinalizationMetrics != null) {
            taskFinalizationMetrics.recordPendingCleanupEntered();
        }
        refreshFinalizationMetrics();
    }

    private void completeFinalization(String taskId) {
        scheduler.unregisterTask(taskId);
        activeTasks.invalidate(taskId);
        pendingFinalizationTasks.remove(taskId);
        pendingFinalizationCleanupTasks.remove(taskId);
        removeListingState(taskId);
        refreshFinalizationMetrics();
    }

    private void retryFinalizationCleanup(String taskId, TaskState state) {
        if (!pendingFinalizationCleanupTasks.containsKey(taskId) && !walReader.exists(taskId)) {
            return;
        }
        finalizeTerminalTask(taskId, state);
    }

    private Long checkpointBaselineMillis(TaskState state) {
        if (state.getLastCheckpointAt() == null) {
            return null;
        }
        return state.getLastCheckpointAt().toEpochMilli();
    }

    @SuppressWarnings("unused")
    private void onTaskEvicted(String taskId, TaskState state, RemovalCause cause) {
        if (cause == RemovalCause.EXPLICIT || cause == RemovalCause.REPLACED || state == null) {
            return;
        }
        if (state.getStatus() == TaskState.TaskStatus.RUNNING) {
            log.warn("Running task evicted from cache taskId={} cause={}", taskId, cause);
            if (state.getLatestCheckpointId() == null
                    || dirtySetTracker.hasDirty(taskId)) {
                Set<String> inProgress = evictionCheckpointGuard.get();
                if (!inProgress.add(taskId)) {
                    log.warn("Skipping recursive emergency checkpoint taskId={}", taskId);
                    return;
                }
                try {
                    snapshotService.checkpointLoadedTask(taskId, state);
                } catch (Exception e) {
                    SnapshotHealthLogSupport.logCheckpointFailure(log,
                            "emergency-checkpoint-on-eviction", taskId,
                            state.getLatestCheckpointId(), e);
                } finally {
                    inProgress.remove(taskId);
                    if (inProgress.isEmpty()) {
                        evictionCheckpointGuard.remove();
                    }
                }
            }
        }
    }

    private boolean isTerminalStatus(TaskState.TaskStatus status) {
        return status == TaskState.TaskStatus.COMPLETED
                || status == TaskState.TaskStatus.FAILED;
    }

    private boolean isActiveTaskForListing(TaskState state) {
        return !isTerminalStatus(state.getStatus())
                && state.getFinalizationStatus() != TaskState.TaskFinalizationStatus.PENDING_FINALIZATION;
    }

    private TaskListingEntry toListingEntry(TaskState state) {
        return new TaskListingEntry(
                state.getTaskId(),
                state.getDescription(),
                state.getNamespace(),
                state.getCreatedAt());
    }

    private void recordListingState(TaskState state) {
        if (state == null || state.getTaskId() == null || !isActiveTaskForListing(state)) {
            return;
        }
        taskListingIndex.put(state.getTaskId(), toListingEntry(state));
        persistTaskListingIndex();
    }

    private void removeListingState(String taskId) {
        if (taskId == null) {
            return;
        }
        if (taskListingIndex.remove(taskId) != null) {
            persistTaskListingIndex();
        }
    }

    private void loadTaskListingIndex() {
        try {
            byte[] data = l3.getBytes(ACTIVE_TASK_INDEX_KEY);
            if (data == null || data.length == 0) {
                taskListingIndexSnapshotPresent = false;
                return;
            }
            taskListingIndexSnapshotPresent = true;
            TaskListingSnapshot snapshot = kryoSerializer.deserialize(data, TaskListingSnapshot.class);
            taskListingIndex.clear();
            if (snapshot != null && snapshot.entries != null) {
                snapshot.entries.forEach(entry -> taskListingIndex.put(entry.getTaskId(), entry));
            }
        } catch (Exception e) {
            log.warn("Active task listing index load failed: {}", e.getMessage());
        }
    }

    private void persistTaskListingIndex() {
        try {
            taskListingIndexSnapshotPresent = true;
            l3.putBytes(ACTIVE_TASK_INDEX_KEY,
                    kryoSerializer.serialize(new TaskListingSnapshot(List.copyOf(taskListingIndex.values()))));
        } catch (Exception e) {
            log.warn("Active task listing index persist failed: {}", e.getMessage());
        }
    }

    private void refreshFinalizationMetrics() {
        if (taskFinalizationMetrics == null) {
            return;
        }
        taskFinalizationMetrics.setPendingFinalizationCount(pendingFinalizationTasks.size());
        taskFinalizationMetrics.setPendingCleanupCount(pendingFinalizationCleanupTasks.size());
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private static String jsonPayload(String... keyValues) {
        if ((keyValues.length & 1) != 0) {
            throw new IllegalArgumentException(
                    "jsonPayload requires an even number of key/value arguments");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1] != null ? keyValues[i + 1] : "");
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize WAL payload", e);
        }
    }

    // ========================================================================
    // Record
    // ========================================================================

    public record TaskPage(List<TaskState> items, int page, int size, long total) {
        public TaskPage {
            items = List.copyOf(items);
        }

        public boolean hasNext() {
            return ((long) page + 1L) * size < total;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TaskListingEntry {
        private String taskId;
        private String description;
        private String namespace;
        private Instant createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TaskListingSnapshot {
        private List<TaskListingEntry> entries;
    }
}
