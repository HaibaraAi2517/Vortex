package com.vortex.storage.api;

import com.vortex.common.model.MemoryFragment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Atomic, durable compare-and-set of one namespace/key and its searchable fragment. */
public interface FactMemoryStore {
    MemoryFact putFact(String key, MemoryFragment fragment, long expectedRevision, Instant expiresAt);
    Optional<MemoryFact> getFact(String namespace, String key);
    /** Key-ordered page; deleted tombstones are never listed. Cursor is exclusive. */
    List<MemoryFact> listFacts(String namespace, String afterKey, int limit, boolean includeInactive);
    List<MemoryFact> factHistory(String namespace, String key, long beforeRevision, int limit);
    MemoryFact retractFact(String namespace, String key, long expectedRevision);
    /** Erases content/history, retaining only a version tombstone to prevent stale retries. */
    MemoryFact deleteFact(String namespace, String key, long expectedRevision);
}
