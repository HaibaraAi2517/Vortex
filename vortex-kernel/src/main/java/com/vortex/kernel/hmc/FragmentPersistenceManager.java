package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class FragmentPersistenceManager {

    private final L2WarmStore l2;
    private final L3ColdStore l3;
    private final FileBackedDeadLetterQueue deadLetterQueue;
    private final FileBackedProcessedTaskStore processedTaskStore;
    private final MemorySloTracker sloTracker;
    private final boolean replayOnStartup;
    private final Executor asyncExecutor;

    public FragmentPersistenceManager(
            L2WarmStore l2,
            L3ColdStore l3,
            FileBackedDeadLetterQueue deadLetterQueue,
            FileBackedProcessedTaskStore processedTaskStore,
            MemorySloTracker sloTracker,
            @Value("${vortex.kernel.persistence.replay-on-startup:true}") boolean replayOnStartup) {
        this(l2, l3, deadLetterQueue, processedTaskStore, sloTracker, replayOnStartup,
                Executors.newVirtualThreadPerTaskExecutor());
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
        FileBackedDeadLetterQueue.ReplayReport report = deadLetterQueue.replay(this::persistTask);
        int remaining = deadLetterQueue.size();
        sloTracker.recordRecoveryResult(remaining == 0 && report.discardedCount() == 0);
        log.info("Persistence DLQ replayed={} retried={} discarded={} remaining={} pendingBefore={}",
                report.recoveredCount(), report.retriedCount(), report.discardedCount(), remaining, pending);
        return report.recoveredCount();
    }

    void persistOrEnqueue(FragmentPersistenceTask task) {
        try {
            persistTask(task);
        } catch (Exception ex) {
            boolean queued = deadLetterQueue.enqueueForRetry(task, ex);
            if (queued) {
                log.error("Fragment persistence failed; queued for replay idempotencyKey={} fragmentId={} reason={} attempts={}: {}",
                        task.getIdempotencyKey(),
                        task.getFragment().getId(),
                        task.getReason(),
                        task.getAttemptCount(),
                        ex.getMessage());
                return;
            }
            sloTracker.recordRecoveryResult(false);
            log.error("Fragment persistence failed permanently; dropping task idempotencyKey={} fragmentId={} reason={} attempts={}: {}",
                    task.getIdempotencyKey(),
                    task.getFragment().getId(),
                    task.getReason(),
                    task.getAttemptCount(),
                    ex.getMessage());
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
        sloTracker.recordRecoveryResult(true);
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
}
