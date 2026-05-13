package com.vortex.storage.api;

import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * L3 Cold Store — durable object storage for full archives and snapshots.
 * Implementations must be thread-safe.
 */
public interface L3ColdStore {

    // ---- Memory fragment archival ----

    /** Persist a fragment to cold storage. */
    void archiveFragment(MemoryFragment fragment);

    /** Retrieve a fragment from cold storage. */
    Optional<MemoryFragment> retrieveFragment(String id);

    // ---- Task snapshot / checkpoint ----

    /**
     * Persist a task state snapshot.
     * @return the checkpoint object key (used for recovery).
     */
    String saveCheckpoint(TaskState state);

    /** Load a previously saved checkpoint. */
    Optional<TaskState> loadCheckpoint(String checkpointId);

    /** Delete a checkpoint (e.g., after successful task completion). */
    void deleteCheckpoint(String checkpointId);

    /** Store arbitrary bytes in cold storage. */
    default void putBytes(String key, byte[] data) {
        throw new UnsupportedOperationException("Binary object storage not supported");
    }

    /** Retrieve arbitrary bytes from cold storage. */
    default byte[] getBytes(String key) {
        return null;
    }

    /**
     * List durable checkpoint metadata for a task, oldest to newest when available.
     *
     * Implementations may return lightweight reconstructed metadata for legacy
     * checkpoints that predate explicit metadata persistence.
     */
    default List<CheckpointMetadata> listCheckpointMetadata(String taskId) {
        return List.of();
    }

    /**
     * List task IDs that currently have at least one checkpoint in cold storage.
     */
    default Set<String> listTaskIdsWithCheckpoints() {
        return Set.of();
    }
}
