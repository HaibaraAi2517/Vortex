package com.vortex.storage.api;

import com.vortex.common.model.MemoryFragment;

import java.util.List;
import java.util.Optional;

/**
 * L2 Warm Store — vector database for semantic retrieval.
 * Implementations must be thread-safe.
 */
public interface L2WarmStore {

    /** Upsert a fragment (insert or update by id). */
    void upsert(MemoryFragment fragment);

    /** Semantic search: return top-k fragments most similar to the query embedding. */
    List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK);

    /** Retrieve by exact id. */
    Optional<MemoryFragment> get(String id);

    /** Delete a fragment. */
    void delete(String id);

    /**
     * Vector dimension expected by the backing store.
     * Returns -1 when the implementation does not expose this metadata.
     */
    default int vectorDimension() {
        return -1;
    }
}
