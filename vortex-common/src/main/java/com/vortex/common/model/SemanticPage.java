package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A semantic page groups up to 10 semantically related MemoryFragments.
 *
 * Analogy: Linux memory page — the unit of transfer between L1/L2/L3.
 * Each page has a centroid (mean embedding of its fragments) used for
 * semantic-neighborhood prefetch and eviction scoring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticPage {

    /** Unique page identifier, derived from centroid hash. */
    private String pageId;

    /** Mean of all fragment embeddings in this page (512-dim, L2-normalized). */
    private float[] centroid;

    /** Fragment IDs belonging to this page (max {@code PAGE_SIZE} = 10). */
    @Builder.Default
    private Set<String> fragmentIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** DAG node IDs that reference this page. */
    @Builder.Default
    private Set<String> dagNodeIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Cumulative access count (read + write). */
    @Builder.Default
    private long accessCount = 0;

    /** Epoch millis of last read or write. */
    @Builder.Default
    private long lastAccessTime = System.currentTimeMillis();

    /** Current residency state. */
    @Builder.Default
    private PageState state = PageState.BUILDING;

    /**
     * Co-access statistics: fragmentId → number of times co-accessed with
     * a fragment in this page. Used for page re-organization decisions.
     */
    @Builder.Default
    private Map<String, Integer> coAccessStats = new ConcurrentHashMap<>();

    /** Maximum fragments per page. */
    public static final int PAGE_SIZE = 10;

    // ---- Static helpers ----

    /**
     * Build a deterministic pageId from a centroid vector.
     * Uses first 8 dimensions hashed into a UUID.
     */
    public static String buildPageId(float[] centroid) {
        if (centroid == null || centroid.length == 0) {
            return UUID.randomUUID().toString();
        }
        ByteBuffer buf = ByteBuffer.allocate(centroid.length * 4);
        for (float v : centroid) {
            buf.putFloat(v);
        }
        byte[] bytes = buf.array();
        long msb = 0, lsb = 0;
        // Simple hash: combine every 8 bytes into the two longs of a UUID
        for (int i = 0; i < bytes.length; i += 16) {
            msb ^= bytesToLong(bytes, i);
            lsb ^= bytesToLong(bytes, Math.min(i + 8, bytes.length - 8));
        }
        // Ensure version 4 UUID format
        msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private static long bytesToLong(byte[] bytes, int offset) {
        long val = 0;
        for (int i = 0; i < 8; i++) {
            int idx = offset + i;
            val = (val << 8) | (idx < bytes.length ? (bytes[idx] & 0xFF) : 0);
        }
        return val;
    }

    // ---- Mutator helpers ----

    public void recordAccess() {
        accessCount++;
        lastAccessTime = System.currentTimeMillis();
    }

    public void addFragment(String fragmentId) {
        fragmentIds.add(fragmentId);
    }

    public void removeFragment(String fragmentId) {
        fragmentIds.remove(fragmentId);
    }

    public void associateDagNode(String nodeId) {
        dagNodeIds.add(nodeId);
    }

    public void recordCoAccess(String fragmentId) {
        coAccessStats.merge(fragmentId, 1, Integer::sum);
    }
}
