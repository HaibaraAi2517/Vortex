package com.vortex.storage.api;

import com.vortex.common.model.MemoryFragment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * L1 Hot Store — in-process, token-weighted cache.
 * Implementations must be thread-safe.
 */
public interface L1HotStore {

    /** Store or update a fragment. */
    void put(MemoryFragment fragment);

    /** Store or update a fragment, optionally preserving its existing last-access timestamp. */
    default void put(MemoryFragment fragment, boolean recordAccess) {
        put(fragment);
    }

    /** Retrieve by id. */
    Optional<MemoryFragment> get(String id);

    /** Retrieve by id without updating recency metadata when supported. */
    default Optional<MemoryFragment> peek(String id) {
        return get(id);
    }

    /** Return all fragments in the given namespace. */
    List<MemoryFragment> getAll(String namespace);

    /** Remove a fragment (called during eviction). */
    void remove(String id);

    /** Current total token count across all cached fragments. */
    long currentTokenCount();

    /** Maximum token capacity of this store. */
    long maxTokenCapacity();

    /** Invalidate all entries (for testing / namespace reset). */
    void clear(String namespace);
}
