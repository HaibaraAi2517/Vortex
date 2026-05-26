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

    /** Delete a fragment from cold storage when supported. */
    default void deleteFragment(String id) {
        throw new UnsupportedOperationException("Fragment deletion not supported");
    }

    // ---- Task snapshot / checkpoint ----

    /**
     * Persist a task state snapshot.
     * @return the implementation-defined checkpoint ID.
     */
    String saveCheckpoint(TaskState state);

    /**
     * Persist a task state snapshot together with explicit checkpoint metadata.
     * Implementations should preserve the provided checkpoint ID and metadata fields
     * when possible.
     */
    default CheckpointMetadata saveCheckpointWithMetadata(TaskState state, CheckpointMetadata meta) {
        String checkpointId = saveCheckpoint(state);
        meta.setCheckpointId(checkpointId);
        return meta;
    }

    /**
     * Persist arbitrary checkpoint payload bytes with explicit metadata.
     * Intended for DELTA checkpoints or other non-TaskState payloads.
     */
    default CheckpointMetadata saveCheckpointBytesWithMetadata(byte[] data, CheckpointMetadata meta) {
        throw new UnsupportedOperationException("Checkpoint byte storage not supported");
    }

    /**
     * Load a previously saved checkpoint from a task-scoped reference or object key.
     * Typical references are {@code taskId/checkpointId} or a full checkpoint key.
     */
    Optional<TaskState> loadCheckpoint(String checkpointRef);

    /** Delete a checkpoint from a task-scoped reference or object key. */
    void deleteCheckpoint(String checkpointRef);

    /** Delete a checkpoint using known metadata. */
    default void deleteCheckpoint(CheckpointMetadata meta) {
        deleteCheckpoint(meta.getTaskId() + "/" + meta.getCheckpointId());
    }

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
