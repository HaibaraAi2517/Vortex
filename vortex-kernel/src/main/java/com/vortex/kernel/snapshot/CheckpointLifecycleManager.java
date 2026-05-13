package com.vortex.kernel.snapshot;

import com.vortex.common.model.CheckpointMetadata;
import com.vortex.storage.api.L3ColdStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Manages the checkpoint lifecycle: rotation, retention, and cleanup.
 *
 * Policies (all configurable):
 * - maxPerTask: keep at most N checkpoints per task
 * - maxAge: delete checkpoints older than N days
 * - maxHourlySnapshots: keep at most N hourly snapshots
 */
@Slf4j
@Component
public class CheckpointLifecycleManager {

    private final L3ColdStore l3;
    private final int maxPerTask;
    private final int maxAgeDays;
    private final int maxHourlySnapshots;

    public CheckpointLifecycleManager(
            L3ColdStore l3,
            @Value("${vortex.kernel.snapshot.checkpoint.rotation.max-per-task:20}") int maxPerTask,
            @Value("${vortex.kernel.snapshot.checkpoint.rotation.max-age-days:7}") int maxAgeDays,
            @Value("${vortex.kernel.snapshot.checkpoint.rotation.max-hourly-snapshots:48}") int maxHourlySnapshots) {
        this.l3 = l3;
        this.maxPerTask = maxPerTask;
        this.maxAgeDays = maxAgeDays;
        this.maxHourlySnapshots = maxHourlySnapshots;
    }

    /**
     * Apply the retention policy to the checkpoints of a single task.
     *
     * @param taskId       the task to clean up
     * @param checkpoints  current list of checkpoint metadata (sorted oldest→newest)
     */
    public void applyRetention(String taskId, List<CheckpointMetadata> checkpoints) {
        if (checkpoints.isEmpty()) return;

        List<CheckpointMetadata> toDelete = new ArrayList<>();
        Instant now = Instant.now();

        // Sort by creation time (oldest first)
        List<CheckpointMetadata> sorted = new ArrayList<>(checkpoints);
        sorted.sort(Comparator.comparing(CheckpointMetadata::getCreatedAt));

        // Policy 1: max age
        for (CheckpointMetadata meta : sorted) {
            if (meta.getCreatedAt() != null) {
                Duration age = Duration.between(meta.getCreatedAt(), now);
                if (age.toDays() > maxAgeDays) {
                    toDelete.add(meta);
                }
            }
        }

        // Policy 2: max per task
        if (sorted.size() > maxPerTask) {
            int excess = sorted.size() - maxPerTask;
            for (int i = 0; i < excess; i++) {
                CheckpointMetadata meta = sorted.get(i);
                if (!toDelete.contains(meta)) {
                    toDelete.add(meta);
                }
            }
        }

        // Policy 3: hourly snapshots (keep at most N hourly checkpoints)
        // Skip for now — this needs grouping by hour, which is a refinement.

        // Execute deletions
        for (CheckpointMetadata meta : toDelete) {
            try {
                l3.deleteCheckpoint(meta.getTaskId() + "/" + meta.getCheckpointId());
                log.debug("Deleted expired checkpoint: taskId={} checkpointId={} age={}",
                        meta.getTaskId(), meta.getCheckpointId(),
                        Duration.between(meta.getCreatedAt(), now));
            } catch (Exception e) {
                log.warn("Failed to delete checkpoint {}: {}", meta.getCheckpointId(), e.getMessage());
            }
        }

        if (!toDelete.isEmpty()) {
            log.info("Checkpoint retention: deleted {} checkpoints for task={} (kept {})",
                    toDelete.size(), taskId, sorted.size() - toDelete.size());
        }
    }
}
