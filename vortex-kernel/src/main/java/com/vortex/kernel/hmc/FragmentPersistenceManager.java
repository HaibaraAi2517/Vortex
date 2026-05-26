package com.vortex.kernel.hmc;

import com.vortex.common.health.MemoryHealthCodes;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.health.MemoryDurabilityLogSupport;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class FragmentPersistenceManager {

    private static final int DEFAULT_MAX_CONCURRENT_PERSISTENCE = 16;

    private final L2WarmStore l2;
    private final L3ColdStore l3;
    private final FileBackedDeadLetterQueue deadLetterQueue;
    private final FileBackedProcessedTaskStore processedTaskStore;
    private final MemorySloTracker sloTracker;
    private final boolean replayOnStartup;
    private final Executor asyncExecutor;

    @Autowired
    public FragmentPersistenceManager(
            L2WarmStore l2,
            L3ColdStore l3,
            FileBackedDeadLetterQueue deadLetterQueue,
            FileBackedProcessedTaskStore processedTaskStore,
            MemorySloTracker sloTracker,
            @Value("${vortex.kernel.persistence.replay-on-startup:true}") boolean replayOnStartup) {
        this(l2, l3, deadLetterQueue, processedTaskStore, sloTracker, replayOnStartup,
                newBoundedExecutor(DEFAULT_MAX_CONCURRENT_PERSISTENCE));
    }

    FragmentPersistenceManager(
            L2WarmStore l2,
            L3ColdStore l3,
            FileBackedDeadLetterQueue deadLetterQueue,
            FileBackedProcessedTaskStore processedTaskStore,
            MemorySloTracker sloTracker,
            boolean replayOnStartup,
            Executor asyncExecutor) {
        this.l2 = l2;
        this.l3 = l3;
        this.deadLetterQueue = deadLetterQueue;
        this.processedTaskStore = processedTaskStore;
        this.sloTracker = sloTracker;
        this.replayOnStartup = replayOnStartup;
        this.asyncExecutor = asyncExecutor;
    }

    @PostConstruct
    public void init() {
        if (replayOnStartup) {
            replayPendingTasks();
        }
    }

    public void persistAsync(MemoryFragment fragment, String reason) {
        FragmentPersistenceTask task = buildTask(fragment, reason);
        CompletableFuture.runAsync(() -> persistOrEnqueue(task), asyncExecutor)
                .exceptionally(ex -> {
                    log.error("Async persistence scheduling failed for id={} reason={}: {}",
                            fragment.getId(), reason, ex.getMessage());
                    return null;
                });
    }

    public int replayPendingTasks() {
        int pending = deadLetterQueue.size();
        if (pending == 0) {
            return 0;
        }
        try {
            FileBackedDeadLetterQueue.ReplayReport report = deadLetterQueue.replay(this::persistTask);
            int remaining = deadLetterQueue.size();
            for (int i = 0; i < report.discardedCount(); i++) {
                sloTracker.recordPersistenceResult(false);
            }
            Map<String, Object> attributes = Map.of(
                    "pendingBefore", pending,
                    "replayed", report.recoveredCount(),
                    "retried", report.retriedCount(),
                    "discarded", report.discardedCount(),
                    "remaining", remaining);
            if (report.discardedCount() > 0) {
                MemoryDurabilityLogSupport.logCritical(
                        log,
                        MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                        MemoryDurabilityLogSupport.CHAIN_MEMORY_PERSISTENCE,
                        MemoryDurabilityLogSupport.PHASE_DLQ_REPLAY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "DLQ_REPLAY_EXHAUSTED",
                        "Persistence DLQ replay discarded one or more tasks after retry exhaustion.",
                        null,
                        attributes);
            } else if (remaining > 0) {
                MemoryDurabilityLogSupport.logWarning(
                        log,
                        MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                        MemoryDurabilityLogSupport.CHAIN_MEMORY_PERSISTENCE,
                        MemoryDurabilityLogSupport.PHASE_DLQ_REPLAY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "DLQ_REPLAY_PENDING",
                        "Persistence DLQ replay left tasks pending for later retry.",
                        null,
                        attributes);
            } else {
                MemoryDurabilityLogSupport.logRecovered(
                        log,
                        MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                        MemoryDurabilityLogSupport.CHAIN_MEMORY_PERSISTENCE,
                        MemoryDurabilityLogSupport.PHASE_COMPLETE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Persistence DLQ replay drained the backlog.",
                        attributes);
            }
            return report.recoveredCount();
        } catch (RuntimeException e) {
            MemoryDurabilityLogSupport.logCritical(
                    log,
                    MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                    MemoryDurabilityLogSupport.CHAIN_MEMORY_PERSISTENCE,
                    MemoryDurabilityLogSupport.PHASE_DLQ_REPLAY,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "DLQ_REPLAY_FAILED",
                    "Persistence DLQ replay failed before backlog reconciliation completed.",
                    e,
                    Map.of("pendingBefore", pending));
            throw e;
        }
    }

    void persistOrEnqueue(FragmentPersistenceTask task) {
        try {
            persistTask(task);
        } catch (Exception persistFailure) {
            String failedPhase = persistencePhase(task);
            try {
                boolean queued = deadLetterQueue.enqueueForRetry(task, persistFailure);
                if (queued) {
                    MemoryDurabilityLogSupport.logWarning(
                            log,
                            MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                            MemoryDurabilityLogSupport.CHAIN_MEMORY_PERSISTENCE,
                            failedPhase,
                            null,
                            null,
                            task.getFragment().getId(),
                            task.getIdempotencyKey(),
                            null,
                            failureReasonForPhase(failedPhase),
                            "Fragment persistence failed and was deferred to the DLQ for retry.",
                            persistFailure,
                            Map.of(
                                    "attempts", task.getAttemptCount(),
                                    "reason", task.getReason(),
                                    "deferredPhase", MemoryDurabilityLogSupport.PHASE_DLQ_ENQUEUE));
                    return;
                }
                sloTracker.recordPersistenceResult(false);
                MemoryDurabilityLogSupport.logCritical(
                        log,
                        MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                        MemoryDurabilityLogSupport.CHAIN_MEMORY_PERSISTENCE,
                        MemoryDurabilityLogSupport.PHASE_DLQ_DROP,
                        null,
                        null,
                        task.getFragment().getId(),
                        task.getIdempotencyKey(),
                        null,
                        "DLQ_MAX_ATTEMPTS_EXHAUSTED",
                        "Fragment persistence was dropped after exhausting the DLQ retry budget.",
                        persistFailure,
                        Map.of(
                                "attempts", task.getAttemptCount(),
                                "reason", task.getReason(),
                                "failedPhase", failedPhase));
            } catch (Exception enqueueFailure) {
                sloTracker.recordPersistenceResult(false);
                MemoryDurabilityLogSupport.logCritical(
                        log,
                        MemoryHealthCodes.MEMORY_PERSISTENCE_SUCCESS_RATE_LOW,
                        MemoryDurabilityLogSupport.CHAIN_MEMORY_PERSISTENCE,
                        MemoryDurabilityLogSupport.PHASE_DLQ_ENQUEUE,
                        null,
                        null,
                        task.getFragment().getId(),
                        task.getIdempotencyKey(),
                        null,
                        "DLQ_ENQUEUE_FAILED",
                        "Fragment persistence failed and the DLQ fallback could not accept the retry task.",
                        enqueueFailure,
                        Map.of(
                                "attempts", task.getAttemptCount(),
                                "reason", task.getReason(),
                                "failedPhase", failedPhase,
                                "initialFailure", persistFailure.getMessage() == null ? "n/a" : persistFailure.getMessage()));
            }
        }
    }

    private void persistTask(FragmentPersistenceTask task) {
        if (processedTaskStore.contains(task.getIdempotencyKey())) {
            log.debug("Skipping already processed persistence task idempotencyKey={} fragmentId={}",
                    task.getIdempotencyKey(), task.getFragment().getId());
            return;
        }
        MemoryFragment fragment = task.getFragment();
        if (!task.isL2Persisted() && fragment.getEmbedding() != null) {
            l2.upsert(fragment);
            task.setL2Persisted(true);
        }
        if (!task.isL3Archived()) {
            l3.archiveFragment(fragment);
            task.setL3Archived(true);
        }
        processedTaskStore.markProcessed(task.getIdempotencyKey());
        sloTracker.recordPersistenceResult(true);
        log.debug("Fragment persisted idempotencyKey={} fragmentId={} reason={} attempts={}",
                task.getIdempotencyKey(), fragment.getId(), task.getReason(), task.getAttemptCount());
    }

    private FragmentPersistenceTask buildTask(MemoryFragment fragment, String reason) {
        String normalizedReason = reason == null ? "unknown" : reason.toLowerCase(Locale.ROOT).replace(' ', '-');
        String idempotencyKey = "%s:%s:%s".formatted(
                fragment.getNamespace(),
                fragment.getId(),
                normalizedReason);
        return FragmentPersistenceTask.builder()
                .idempotencyKey(idempotencyKey)
                .reason(normalizedReason)
                .fragment(fragment)
                .createdAt(Instant.now())
                .attemptCount(0)
                .l2Persisted(false)
                .l3Archived(false)
                .build();
    }

    private String persistencePhase(FragmentPersistenceTask task) {
        if (!task.isL2Persisted() && task.getFragment().getEmbedding() != null) {
            return MemoryDurabilityLogSupport.PHASE_L2_UPSERT;
        }
        return MemoryDurabilityLogSupport.PHASE_L3_ARCHIVE;
    }

    private String failureReasonForPhase(String phase) {
        if (MemoryDurabilityLogSupport.PHASE_L2_UPSERT.equals(phase)) {
            return "L2_UPSERT_FAILED";
        }
        if (MemoryDurabilityLogSupport.PHASE_L3_ARCHIVE.equals(phase)) {
            return "L3_ARCHIVE_FAILED";
        }
        return "PERSISTENCE_FAILED";
    }

    private static Executor newBoundedExecutor(int maxConcurrentPersistence) {
        int concurrency = Math.max(1, maxConcurrentPersistence);
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(concurrency * 4),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
