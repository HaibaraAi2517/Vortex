package com.vortex.storage.api;

import com.vortex.common.model.MemoryFragment;

import java.util.Collection;
import java.util.function.BiConsumer;

/**
 * Administrative operations for L1 store implementations.
 */
public interface L1HotStoreAdmin {

    enum EvictionCause {
        SIZE,
        EXPIRED,
        EXPLICIT,
        REPLACED,
        COLLECTED,
        OTHER
    }

    Collection<MemoryFragment> allFragments();

    void registerEvictionListener(BiConsumer<MemoryFragment, EvictionCause> listener);
}
