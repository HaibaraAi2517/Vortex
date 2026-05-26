package com.vortex.kernel.snapshot;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatically triggers checkpoints for active tasks based on action count or time interval.
 *
 * Two triggers:
 * 1. actionsSinceLastCheckpoint >= maxActionsBetweenCheckpoints
 * 2. millisSinceLastCheckpoint >= maxMillisBetweenCheckpoints
 *
 * Also performs graceful shutdown checkpoint via @PreDestroy.
 */
@Slf4j
@Component
public class CheckpointScheduler {

    private final int maxActionsBetween;
    private final long maxMillisBetween;
    private final boolean enabled;

    /** Per-task action count since last checkpoint. */
    private final ConcurrentHashMap<String, AtomicLong> actionCounters = new ConcurrentHashMap<>();

    /** Per-task timestamp of last checkpoint. */
    private final ConcurrentHashMap<String, Long> lastCheckpointTimes = new ConcurrentHashMap<>();

    /** Registered tasks that need scheduling. */
    private final ConcurrentHashMap<String, SnapshotService> taskServices = new ConcurrentHashMap<>();

    public CheckpointScheduler(
            @Value("${vortex.kernel.snapshot.scheduler.max-actions-between:50}") int maxActionsBetween,
            @Value("${vortex.kernel.snapshot.scheduler.max-millis-between:60000}") long maxMillisBetween,
            @Value("${vortex.kernel.snapshot.scheduler.enabled:true}") boolean enabled) {
        this.maxActionsBetween = maxActionsBetween;
        this.maxMillisBetween = maxMillisBetween;
        this.enabled = enabled;
    }

    /**
     * Register a task for automatic checkpointing.
     */
    public void registerTask(String taskId, SnapshotService service) {
        if (service != null) {
            taskServices.put(taskId, service);
        }
        actionCounters.put(taskId, new AtomicLong(0));
        lastCheckpointTimes.put(taskId, System.currentTimeMillis());
    }

    /**
     * Called after every state-modifying operation (appendNode, completeNode, etc.).
     */
    public void recordAction(String taskId) {
        AtomicLong counter = actionCounters.get(taskId);
        if (counter == null) return; // Task not registered (no auto-checkpoint)
        counter.incrementAndGet();
    }

    /**
     * Reset counters after a checkpoint.
     */
    public void onCheckpoint(String taskId) {
        AtomicLong counter = actionCounters.get(taskId);
        if (counter != null) counter.set(0);
        lastCheckpointTimes.put(taskId, System.currentTimeMillis());
    }

    /**
     * Unregister a task (on completion or removal).
     */
    public void unregisterTask(String taskId) {
        taskServices.remove(taskId);
        actionCounters.remove(taskId);
        lastCheckpointTimes.remove(taskId);
    }

    /**
     * Periodic scan: check all registered tasks and trigger checkpoint if needed.
     */
    @Scheduled(fixedDelayString = "${vortex.kernel.snapshot.scheduler.fixed-delay-ms:10000}",
            initialDelayString = "${vortex.kernel.snapshot.scheduler.initial-delay-ms:30000}")
    public void scheduledScan() {
        if (!enabled) return;

        List<String> tasksNeedingCheckpoint = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (var entry : taskServices.entrySet()) {
            String taskId = entry.getKey();
            AtomicLong counter = actionCounters.get(taskId);
            Long lastCheckpoint = lastCheckpointTimes.get(taskId);

            boolean actionTrigger = counter != null && counter.get() >= maxActionsBetween;
            boolean timeTrigger = lastCheckpoint != null && (now - lastCheckpoint) >= maxMillisBetween;

            if (actionTrigger || timeTrigger) {
                tasksNeedingCheckpoint.add(taskId);
            }
        }

        for (String taskId : tasksNeedingCheckpoint) {
            SnapshotService service = taskServices.get(taskId);
            if (service != null && service.isTaskLoadedForCheckpoint(taskId)) {
                try {
                    service.checkpoint(taskId);
                    log.debug("Auto-checkpoint triggered for task={}", taskId);
                } catch (Exception e) {
                    SnapshotHealthLogSupport.logCheckpointFailure(log, "auto-checkpoint", taskId, null, e);
                }
            } else if (service != null) {
                log.debug("Auto-checkpoint skipped for unloaded task={}", taskId);
            }
        }
    }

    /**
     * Graceful shutdown: checkpoint all active tasks.
     */
    @PreDestroy
    public void shutdownCheckpoint() {
        long loadedTasks = taskServices.entrySet().stream()
                .filter(entry -> entry.getValue().isTaskLoadedForCheckpoint(entry.getKey()))
                .count();
        log.info("Graceful shutdown: checkpointing {} loaded tasks...", loadedTasks);
        for (var entry : taskServices.entrySet()) {
            if (!entry.getValue().isTaskLoadedForCheckpoint(entry.getKey())) {
                log.debug("Shutdown checkpoint skipped for unloaded task={}", entry.getKey());
                continue;
            }
            try {
                entry.getValue().checkpoint(entry.getKey());
                log.info("Shutdown checkpoint created for task={}", entry.getKey());
            } catch (Exception e) {
                SnapshotHealthLogSupport.logCheckpointFailure(log, "shutdown-checkpoint", entry.getKey(), null, e);
            }
        }
    }
}
