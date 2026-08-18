package com.vortex.storage.api;

import com.vortex.common.model.MemoryFragment;

import java.util.Collection;
import java.util.Objects;
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

    default long namespaceTokenCount(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return 0L;
        }
        return allFragments().stream()
                .filter(fragment -> Objects.equals(namespace, fragment.getNamespace()))
                .mapToLong(MemoryFragment::getTokenCount)
                .sum();
    }

    default int namespaceFragmentCount(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return 0;
        }
        return (int) allFragments().stream()
                .filter(fragment -> Objects.equals(namespace, fragment.getNamespace()))
                .count();
    }

    default int activeNamespaceCount() {
        return (int) allFragments().stream()
                .map(MemoryFragment::getNamespace)
                .filter(namespace -> namespace != null && !namespace.isBlank())
                .distinct()
                .count();
    }

    void registerEvictionListener(BiConsumer<MemoryFragment, EvictionCause> listener);
}
