package com.vortex.kernel.health;

import org.slf4j.Logger;

import java.util.Map;
import java.util.TreeMap;

public final class MemoryDurabilityLogSupport {

    public static final String CHAIN_MEMORY_PERSISTENCE = "memory-persistence";
    public static final String CHAIN_CHECKPOINT_RECOVERY = "checkpoint-recovery";

    public static final String PHASE_COMPLETE = "complete";
    public static final String PHASE_L2_UPSERT = "l2-upsert";
    public static final String PHASE_L3_ARCHIVE = "l3-archive";
    public static final String PHASE_DLQ_ENQUEUE = "dlq-enqueue";
    public static final String PHASE_DLQ_REPLAY = "dlq-replay";
    public static final String PHASE_DLQ_DROP = "dlq-drop";

    private MemoryDurabilityLogSupport() {
    }

    public static void logWarning(
            Logger log,
            String healthCode,
            String chain,
            String phase,
            String taskId,
            String checkpointId,
            String fragmentId,
            String idempotencyKey,
            String entryId,
            String failureReason,
            String message,
            Throwable error,
            Map<String, ?> attributes) {
        logDegraded(
                log,
                "warning",
                healthCode,
                chain,
                phase,
                taskId,
                checkpointId,
                fragmentId,
                idempotencyKey,
                entryId,
                failureReason,
                message,
                error,
                attributes);
    }

    public static void logCritical(
            Logger log,
            String healthCode,
            String chain,
            String phase,
            String taskId,
            String checkpointId,
            String fragmentId,
            String idempotencyKey,
            String entryId,
            String failureReason,
            String message,
            Throwable error,
            Map<String, ?> attributes) {
        logDegraded(
                log,
                "critical",
                healthCode,
                chain,
                phase,
                taskId,
                checkpointId,
                fragmentId,
                idempotencyKey,
                entryId,
                failureReason,
                message,
                error,
                attributes);
    }

    public static void logRecovered(
            Logger log,
            String healthCode,
            String chain,
            String phase,
            String taskId,
            String checkpointId,
            String fragmentId,
            String idempotencyKey,
            String entryId,
            String message,
            Map<String, ?> attributes) {
        log.info(
                "memory_durability_recovered healthCode={} chain={} phase={} taskId={} checkpointId={} fragmentId={} idempotencyKey={} entryId={} message={} attributes={}",
                safe(healthCode),
                safe(chain),
                safe(phase),
                safe(taskId),
                safe(checkpointId),
                safe(fragmentId),
                safe(idempotencyKey),
                safe(entryId),
                safe(message),
                normalizeAttributes(attributes));
    }

    private static void logDegraded(
            Logger log,
            String severity,
            String healthCode,
            String chain,
            String phase,
            String taskId,
            String checkpointId,
            String fragmentId,
            String idempotencyKey,
            String entryId,
            String failureReason,
            String message,
            Throwable error,
            Map<String, ?> attributes) {
        String line = "memory_durability_degraded healthCode={} chain={} phase={} severity={} "
                + "taskId={} checkpointId={} fragmentId={} idempotencyKey={} entryId={} "
                + "failureReason={} message={} attributes={}";
        if ("critical".equals(severity)) {
            log.error(
                    line,
                    safe(healthCode),
                    safe(chain),
                    safe(phase),
                    severity,
                    safe(taskId),
                    safe(checkpointId),
                    safe(fragmentId),
                    safe(idempotencyKey),
                    safe(entryId),
                    safe(failureReason),
                    safe(message),
                    normalizeAttributes(attributes),
                    error);
            return;
        }
        log.warn(
                line,
                safe(healthCode),
                safe(chain),
                safe(phase),
                severity,
                safe(taskId),
                safe(checkpointId),
                safe(fragmentId),
                safe(idempotencyKey),
                safe(entryId),
                safe(failureReason),
                safe(message),
                normalizeAttributes(attributes),
                error);
    }

    private static Map<String, Object> normalizeAttributes(Map<String, ?> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new TreeMap<>();
        attributes.forEach((key, value) -> normalized.put(key, value == null ? "n/a" : value));
        return Map.copyOf(normalized);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }
}
