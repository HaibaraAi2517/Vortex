package com.vortex.storage.api;

import java.time.Instant;
import java.util.List;

/** A caller-identified fact. Revision zero means the key has never existed. */
public record MemoryFact(
        String namespace, String key, long revision, String fragmentId,
        String content, List<String> tags, State state,
        Instant createdAt, Instant updatedAt, Instant expiresAt) {
    public enum State { ACTIVE, RETRACTED, DELETED }

    public boolean activeAt(Instant time) {
        return state == State.ACTIVE && (expiresAt == null || expiresAt.isAfter(time));
    }
}
