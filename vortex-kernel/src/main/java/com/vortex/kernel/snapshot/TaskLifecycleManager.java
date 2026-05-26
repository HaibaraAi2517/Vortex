package com.vortex.kernel.snapshot;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.storage.api.L3ColdStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

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

    private final L3ColdStore l3;
    private final IncrementalCheckpointManager checkpointManager;
    private final CheckpointLifecycleManager lifecycleManager;
    private final ActionLogWriter walWriter;
    private final ActionLogReader walReader;
    private final ActionLogTruncator walTruncator;
    private final CheckpointScheduler scheduler;
    private final DirtySetTracker dirtySetTracker;
    private final MemorySloTracker memorySloTracker;
    private SnapshotService snapshotService;
    private RecoveryEngine recoveryEngine;

    /** In-memory registry of active tasks. */
    private final Cache<String, TaskState> activeTasks = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(2))
            .removalListener(this::onTaskEvicted)
            .build();

    /** Durable latest-checkpoint index rebuilt from L3 on startup. */
    private final ConcurrentHashMap<String, String> latestCheckpointIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> checkpointLocks = new ConcurrentHashMap<>();

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
        for (String taskId : l3.listTaskIdsWithCheckpoints()) {
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
        scheduler.registerTask(taskId, snapshotService);

        log.info("Task created taskId={} namespace={}", taskId, namespace);
        return state;
    }

    /**
     * Get the current state of a task. Lazily recovers from L3 if not in memory.
     */
    public Optional<TaskState> getTask(String taskId) {
        TaskState cached = activeTasks.getIfPresent(taskId);
        if (cached != null) {
            return Optional.of(cached);
        }

        String checkpointId = latestCheckpointIds.get(taskId);
        if (checkpointId == null) {
            return Optional.empty();
        }
        TaskState recovered = recoveryEngine.doRecover(taskId, checkpointId);
        activeTasks.put(taskId, recovered);
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
        return new ArrayList<>(activeTasks.asMap().values());
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

        Map<String, TaskState> visibleTasks = new LinkedHashMap<>(activeTasks.asMap());
        for (String taskId : latestCheckpointIds.keySet()) {
            visibleTasks.computeIfAbsent(taskId, this::loadTaskForListing);
        }

        List<TaskState> ordered = visibleTasks.values().stream()
                .filter(Objects::nonNull)
                .filter(this::isActiveTaskForListing)
                .sorted(Comparator.comparing(
                                TaskState::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(TaskState::getTaskId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        int total = ordered.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        return new TaskPage(ordered.subList(fromIndex, toIndex), page, size, total);
    }

    /**
     * Mark a task as completed: final checkpoint, WAL close, and cleanup.
     */
    public void completeTask(String taskId) {
        TaskState state = requireTask(taskId);
        if (isTerminalStatus(state.getStatus())) {
            return;
        }

        // Final checkpoint
        snapshotService.checkpoint(taskId);

        // Close WAL
        walWriter.close(taskId);

        // Clean up tracking
        scheduler.unregisterTask(taskId);
        checkpointManager.removeTask(taskId);
        latestCheckpointIds.remove(taskId);

        state.setStatus(TaskState.TaskStatus.COMPLETED);
        activeTasks.invalidate(taskId);

        log.info("Task completed taskId={} totalNodes={}", taskId, state.getGraph().nodeCount());
    }

    /**
     * Mark a task as failed: final checkpoint attempt, WAL close, and cleanup.
     */
    public void failTask(String taskId) {
        TaskState state = requireTask(taskId);
        if (isTerminalStatus(state.getStatus())) {
            return;
        }
        String payload = jsonPayload("status", TaskState.TaskStatus.FAILED.name());
        walWriter.append(taskId,
                com.vortex.common.model.ActionLogEntry.OperationType.SET_STATUS, payload);
        state.setStatus(TaskState.TaskStatus.FAILED);
        try {
            snapshotService.checkpoint(taskId);
        } catch (Exception e) {
            SnapshotHealthLogSupport.logCheckpointFailure(log,
                    "final-checkpoint-on-failure", taskId,
                    state.getLatestCheckpointId(), e);
        }
        walWriter.close(taskId);
        scheduler.unregisterTask(taskId);
        activeTasks.invalidate(taskId);
        log.info("Task failed and cleaned up taskId={}", taskId);
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
        return Optional.ofNullable(activeTasks.getIfPresent(taskId));
    }

    String getLatestCheckpointId(String taskId) {
        return latestCheckpointIds.get(taskId);
    }

    void putLatestCheckpointId(String taskId, String checkpointId) {
        latestCheckpointIds.put(taskId, checkpointId);
    }

    void putTask(String taskId, TaskState state) {
        activeTasks.put(taskId, state);
    }

    ConcurrentHashMap<String, ReentrantLock> getCheckpointLocks() {
        return checkpointLocks;
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private TaskState loadTaskForListing(String taskId) {
        return getTask(taskId).orElse(null);
    }

    @SuppressWarnings("unused")
    private void onTaskEvicted(String taskId, TaskState state, RemovalCause cause) {
        if (cause == RemovalCause.EXPLICIT || state == null) {
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
                    snapshotService.checkpoint(taskId);
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
        return !isTerminalStatus(state.getStatus());
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
}
