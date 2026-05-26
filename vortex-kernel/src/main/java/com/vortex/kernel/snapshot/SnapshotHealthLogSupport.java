package com.vortex.kernel.snapshot;

import com.vortex.common.health.MemoryHealthCodes;
import com.vortex.kernel.health.MemoryDurabilityLogSupport;
import org.slf4j.Logger;

import java.util.Map;

final class SnapshotHealthLogSupport {

    static final String HEALTH_CODE = MemoryHealthCodes.CHECKPOINT_RECOVERY_SUCCESS_RATE_LOW;

    private SnapshotHealthLogSupport() {
    }

    static void logRecoveryFailure(
            Logger log,
            String phase,
            String taskId,
            String checkpointId,
            String entryId,
            String failureReason,
            Throwable error) {
        MemoryDurabilityLogSupport.logWarning(
                log,
                HEALTH_CODE,
                MemoryDurabilityLogSupport.CHAIN_CHECKPOINT_RECOVERY,
                phase,
                taskId,
                checkpointId,
                null,
                null,
                entryId,
                failureReason,
                error == null ? "" : error.getMessage(),
                error,
                Map.of());
    }

    static void logCheckpointFailure(
            Logger log,
            String phase,
            String taskId,
            String checkpointId,
            Throwable error) {
        MemoryDurabilityLogSupport.logCritical(
                log,
                HEALTH_CODE,
                MemoryDurabilityLogSupport.CHAIN_CHECKPOINT_RECOVERY,
                phase,
                taskId,
                checkpointId,
                null,
                null,
                null,
                "CHECKPOINT_WRITE_FAILED",
                error == null ? "" : error.getMessage(),
                error,
                Map.of());
    }

    static void logRecoveryPrerequisiteFailure(
            Logger log,
            String phase,
            String taskId,
            String checkpointId,
            Throwable error) {
        MemoryDurabilityLogSupport.logWarning(
                log,
                HEALTH_CODE,
                MemoryDurabilityLogSupport.CHAIN_CHECKPOINT_RECOVERY,
                phase,
                taskId,
                checkpointId,
                null,
                null,
                null,
                "RECOVERY_PREREQUISITE_FAILED",
                error == null ? "" : error.getMessage(),
                error,
                Map.of());
    }

    static void logRecoverySuccess(
            Logger log,
            String taskId,
            String checkpointId,
            CheckpointRecoveryResult recovery,
            int replayed,
            int skipped,
            int totalNodes,
            String currentNodeId) {
        MemoryDurabilityLogSupport.logRecovered(
                log,
                HEALTH_CODE,
                MemoryDurabilityLogSupport.CHAIN_CHECKPOINT_RECOVERY,
                MemoryDurabilityLogSupport.PHASE_COMPLETE,
                taskId,
                checkpointId,
                null,
                null,
                null,
                "Checkpoint recovery completed successfully.",
                Map.of(
                        "mode", recovery.mode(),
                        "deltaDepth", recovery.deltaDepth(),
                        "cursor", currentNodeId == null ? "n/a" : currentNodeId,
                        "walReplayed", replayed,
                        "walSkipped", skipped,
                        "totalNodes", totalNodes));
    }
}
