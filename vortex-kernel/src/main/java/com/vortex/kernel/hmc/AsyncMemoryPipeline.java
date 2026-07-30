package com.vortex.kernel.hmc;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class AsyncMemoryPipeline {

    private static final String BACKPRESSURE_POLICY = "CALLER_RUNS";

    private final HierarchicalMemoryController hmc;
    private final MemoryExtractionService extractionService;
    private final MemorySummaryService summaryService;
    private final ThreadPoolExecutor executor;
    private final int maxStatuses;
    private final int queueCapacity;
    private final AtomicLong callerRunsCount = new AtomicLong();
    private final Map<String, MemoryPipelineStatus> statuses;

    public AsyncMemoryPipeline(
            HierarchicalMemoryController hmc,
            MemoryExtractionService extractionService,
            MemorySummaryService summaryService,
            @Value("${vortex.kernel.memory-pipeline.max-workers:4}") int maxWorkers,
            @Value("${vortex.kernel.memory-pipeline.max-statuses:2048}") int maxStatuses,
            @Value("${vortex.kernel.memory-pipeline.queue-capacity:256}") int queueCapacity) {
        this.hmc = hmc;
        this.extractionService = extractionService;
        this.summaryService = summaryService;
        int workers = Math.max(1, maxWorkers);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(this.queueCapacity),
                Executors.defaultThreadFactory(),
                (task, rejectedExecutor) -> {
                    callerRunsCount.incrementAndGet();
                    if (!rejectedExecutor.isShutdown()) {
                        task.run();
                    }
                });
        this.maxStatuses = Math.max(16, maxStatuses);
        this.statuses = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    }

    public MemoryPipelineStatus submit(MemoryPipelineRequest request) {
        MemoryPipelineRequest normalized = normalize(request);
        HierarchicalMemoryController.L1WriteThrough writeThrough = hmc.stageL1WriteThrough(
                normalized.getContent(),
                normalized.getNamespace(),
                normalized.getTags(),
                normalized.getReasoningChainId(),
                normalized.getPinTtlMillis());
        List<String> writeThroughFragmentIds = writeThrough.fragmentIds();
        MemoryPipelineStatus accepted = MemoryPipelineStatus.builder()
                .pipelineId(normalized.getPipelineId())
                .status(MemoryPipelineStatusCode.ACCEPTED)
                .namespace(normalized.getNamespace())
                .acceptedAt(Instant.now())
                .completedStages(List.of(
                        MemoryPipelineStage.ADMISSION,
                        MemoryPipelineStage.L1_WRITE_THROUGH))
                .fragmentIds(writeThroughFragmentIds)
                .fragmentCount(writeThroughFragmentIds.size())
                .build();
        putStatus(accepted);
        CompletableFuture.runAsync(() -> runPipeline(normalized, true, writeThrough), executor)
                .exceptionally(ex -> {
                    hmc.restoreL1WriteThroughPins(writeThrough);
                    markFailed(normalized.getPipelineId(), ex);
                    return null;
                });
        return snapshot(normalized.getPipelineId()).orElse(accepted);
    }

    public MemoryPipelineStatus processBlocking(MemoryPipelineRequest request) {
        MemoryPipelineRequest normalized = normalize(request);
        MemoryPipelineStatus accepted = MemoryPipelineStatus.builder()
                .pipelineId(normalized.getPipelineId())
                .status(MemoryPipelineStatusCode.ACCEPTED)
                .namespace(normalized.getNamespace())
                .acceptedAt(Instant.now())
                .completedStages(List.of(MemoryPipelineStage.ADMISSION))
                .fragmentIds(List.of())
                .build();
        putStatus(accepted);
        return runPipeline(normalized, true, HierarchicalMemoryController.L1WriteThrough.empty());
    }

    public Optional<MemoryPipelineStatus> snapshot(String pipelineId) {
        if (pipelineId == null || pipelineId.isBlank()) {
            return Optional.empty();
        }
        synchronized (statuses) {
            MemoryPipelineStatus status = statuses.get(pipelineId);
            return status == null ? Optional.empty() : Optional.of(copyStatus(status));
        }
    }

    public PipelineQueueSnapshot queueSnapshot() {
        return new PipelineQueueSnapshot(
                executor.getActiveCount(),
                executor.getPoolSize(),
                executor.getMaximumPoolSize(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity(),
                queueCapacity,
                callerRunsCount.get(),
                BACKPRESSURE_POLICY);
    }

    private MemoryPipelineStatus runPipeline(
            MemoryPipelineRequest request,
            boolean waitForPersistence,
            HierarchicalMemoryController.L1WriteThrough writeThrough) {
        List<String> writeThroughFragmentIds = writeThrough.fragmentIds();
        Instant startedAt = Instant.now();
        EnumSet<MemoryPipelineStage> stages = EnumSet.of(MemoryPipelineStage.ADMISSION);
        if (writeThroughFragmentIds != null && !writeThroughFragmentIds.isEmpty()) {
            stages.add(MemoryPipelineStage.L1_WRITE_THROUGH);
        }
        updateStatus(request.getPipelineId(), MemoryPipelineStatusCode.RUNNING, startedAt, null, stages,
                writeThroughFragmentIds, 0, 0, sizeOf(writeThroughFragmentIds), null);
        try {
            MemoryExtractionService.ExtractionResult extraction = extractionService.extract(request.getContent());
            stages.add(MemoryPipelineStage.EXTRACTION);
            updateStatus(request.getPipelineId(), MemoryPipelineStatusCode.RUNNING, startedAt, null, stages,
                    writeThroughFragmentIds, extraction.units().size(), 0, sizeOf(writeThroughFragmentIds), null);

            MemorySummaryService.SummaryResult summary = summaryService.summarize(extraction);
            stages.add(MemoryPipelineStage.SUMMARY);
            updateStatus(request.getPipelineId(), MemoryPipelineStatusCode.RUNNING, startedAt, null, stages,
                    writeThroughFragmentIds, extraction.units().size(), summary.tokenCount(), sizeOf(writeThroughFragmentIds), null);

            List<String> fragmentIds = hmc.storeProcessed(
                    summary.summaryText(),
                    request.getNamespace(),
                    request.getTags(),
                    request.getReasoningChainId(),
                    request.getPinTtlMillis(),
                    "async-memory-pipeline",
                    waitForPersistence);
            hmc.discardL1WriteThrough(writeThrough);
            stages.addAll(List.of(
                    MemoryPipelineStage.SPLIT,
                    MemoryPipelineStage.EMBEDDING,
                    MemoryPipelineStage.L1_ADMISSION,
                    MemoryPipelineStage.L2_INDEX,
                    MemoryPipelineStage.L3_ARCHIVE));
            return updateStatus(
                    request.getPipelineId(),
                    MemoryPipelineStatusCode.COMPLETED,
                    startedAt,
                    Instant.now(),
                    stages,
                    fragmentIds,
                    extraction.units().size(),
                    summary.tokenCount(),
                    fragmentIds.size(),
                    null);
        } catch (RuntimeException e) {
            hmc.restoreL1WriteThroughPins(writeThrough);
            log.warn("Async memory pipeline failed pipelineId={} namespace={}",
                    request.getPipelineId(), request.getNamespace(), e);
            return markFailed(request.getPipelineId(), e);
        }
    }

    private int sizeOf(List<String> fragmentIds) {
        return fragmentIds == null ? 0 : fragmentIds.size();
    }

    private MemoryPipelineStatus markFailed(String pipelineId, Throwable failure) {
        Optional<MemoryPipelineStatus> current = snapshot(pipelineId);
        EnumSet<MemoryPipelineStage> stages = current
                .map(MemoryPipelineStatus::getCompletedStages)
                .map(list -> list.isEmpty()
                        ? EnumSet.noneOf(MemoryPipelineStage.class)
                        : EnumSet.copyOf(list))
                .orElseGet(() -> EnumSet.of(MemoryPipelineStage.ADMISSION));
        return updateStatus(
                pipelineId,
                MemoryPipelineStatusCode.FAILED,
                current.map(MemoryPipelineStatus::getStartedAt).orElse(null),
                Instant.now(),
                stages,
                current.map(MemoryPipelineStatus::getFragmentIds).orElse(List.of()),
                current.map(MemoryPipelineStatus::getExtractedUnitCount).orElse(0),
                current.map(MemoryPipelineStatus::getSummaryTokenCount).orElse(0),
                current.map(MemoryPipelineStatus::getFragmentCount).orElse(0),
                failure.getClass().getSimpleName() + ": " + (failure.getMessage() == null ? "" : failure.getMessage()));
    }

    private MemoryPipelineRequest normalize(MemoryPipelineRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Memory pipeline request must not be null");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new IllegalArgumentException("Memory pipeline content must not be blank");
        }
        if (request.getNamespace() == null || request.getNamespace().isBlank()) {
            throw new IllegalArgumentException("Memory pipeline namespace must not be blank");
        }
        return MemoryPipelineRequest.builder()
                .pipelineId(request.getPipelineId() == null || request.getPipelineId().isBlank()
                        ? UUID.randomUUID().toString()
                        : request.getPipelineId())
                .content(request.getContent())
                .namespace(request.getNamespace())
                .tags(request.getTags() == null ? List.of() : List.copyOf(request.getTags()))
                .reasoningChainId(request.getReasoningChainId())
                .pinTtlMillis(request.getPinTtlMillis())
                .build();
    }

    private MemoryPipelineStatus updateStatus(
            String pipelineId,
            MemoryPipelineStatusCode statusCode,
            Instant startedAt,
            Instant completedAt,
            EnumSet<MemoryPipelineStage> stages,
            List<String> fragmentIds,
            int extractedUnitCount,
            int summaryTokenCount,
            int fragmentCount,
            String errorMessage) {
        synchronized (statuses) {
            MemoryPipelineStatus previous = statuses.get(pipelineId);
            MemoryPipelineStatus updated = MemoryPipelineStatus.builder()
                    .pipelineId(pipelineId)
                    .status(statusCode)
                    .namespace(previous == null ? null : previous.getNamespace())
                    .acceptedAt(previous == null ? Instant.now() : previous.getAcceptedAt())
                    .startedAt(startedAt == null && previous != null ? previous.getStartedAt() : startedAt)
                    .completedAt(completedAt)
                    .completedStages(List.copyOf(stages))
                    .fragmentIds(fragmentIds == null ? List.of() : List.copyOf(fragmentIds))
                    .extractedUnitCount(extractedUnitCount)
                    .summaryTokenCount(summaryTokenCount)
                    .fragmentCount(fragmentCount)
                    .errorMessage(errorMessage)
                    .build();
            putStatusLocked(updated);
            return copyStatus(updated);
        }
    }

    private void putStatus(MemoryPipelineStatus status) {
        synchronized (statuses) {
            putStatusLocked(status);
        }
    }

    private void putStatusLocked(MemoryPipelineStatus status) {
        statuses.put(status.getPipelineId(), copyStatus(status));
        while (statuses.size() > maxStatuses) {
            Optional<String> evictable = statuses.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(status.getPipelineId()))
                    .filter(entry -> isTerminal(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst();
            if (evictable.isEmpty()) {
                break;
            }
            statuses.remove(evictable.get());
        }
    }

    private boolean isTerminal(MemoryPipelineStatus status) {
        return status != null
                && (status.getStatus() == MemoryPipelineStatusCode.COMPLETED
                || status.getStatus() == MemoryPipelineStatusCode.FAILED);
    }

    private MemoryPipelineStatus copyStatus(MemoryPipelineStatus status) {
        return MemoryPipelineStatus.builder()
                .pipelineId(status.getPipelineId())
                .status(status.getStatus())
                .namespace(status.getNamespace())
                .acceptedAt(status.getAcceptedAt())
                .startedAt(status.getStartedAt())
                .completedAt(status.getCompletedAt())
                .completedStages(status.getCompletedStages() == null
                        ? List.of()
                        : List.copyOf(status.getCompletedStages()))
                .fragmentIds(status.getFragmentIds() == null ? List.of() : List.copyOf(status.getFragmentIds()))
                .extractedUnitCount(status.getExtractedUnitCount())
                .summaryTokenCount(status.getSummaryTokenCount())
                .fragmentCount(status.getFragmentCount())
                .errorMessage(status.getErrorMessage())
                .build();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public record PipelineQueueSnapshot(
            int activeWorkers,
            int poolSize,
            int maxWorkers,
            int queueSize,
            int queueRemainingCapacity,
            int queueCapacity,
            long callerRunsCount,
            String backpressurePolicy) {
    }
}
