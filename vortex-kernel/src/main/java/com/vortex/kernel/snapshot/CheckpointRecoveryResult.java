package com.vortex.kernel.snapshot;

import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.TaskState;

public record CheckpointRecoveryResult(
        TaskState state,
        CheckpointRecoveryMode mode,
        int deltaDepth,
        CheckpointMetadata checkpointMetadata) {
}
