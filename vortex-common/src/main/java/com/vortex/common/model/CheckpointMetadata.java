package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata describing a single checkpoint stored in L3.
 * Kept lightweight so checkpoint history can be listed without loading full snapshots.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointMetadata {

    @Builder.Default
    private String checkpointId = UUID.randomUUID().toString();

    /** The task this checkpoint belongs to. */
    private String taskId;

    /** Monotonic WAL sequence number captured at this checkpoint. */
    private long sequenceNumber;

    @Builder.Default
    private CheckpointType type = CheckpointType.FULL;

    /**
     * For DELTA checkpoints, the ID of the base checkpoint this delta applies to.
     * null for FULL checkpoints.
     */
    private String baseCheckpointId;

    /** Number of DAG nodes at checkpoint time. */
    private int nodeCount;

    /** Number of DAG edges at checkpoint time. */
    private int edgeCount;

    /** Compressed byte size in L3 storage. */
    private long sizeBytes;

    @Builder.Default
    private boolean compressed = true;

    @Builder.Default
    private String compressionAlgorithm = "gzip";

    /** Which branch this checkpoint records. */
    private String branchId;

    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Full object key in L3 (checkpoints/{taskId}/{checkpointId}.kryo). */
    private String l3Key;

    public enum CheckpointType {
        FULL,
        DELTA
    }
}
