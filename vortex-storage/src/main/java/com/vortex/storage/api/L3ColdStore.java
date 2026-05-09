package com.vortex.storage.api;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;

import java.util.Optional;

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
}
