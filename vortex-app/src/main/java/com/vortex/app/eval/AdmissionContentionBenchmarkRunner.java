package com.vortex.app.eval;

import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.kernel.hmc.NamespaceQuotaManager;
import com.vortex.kernel.hmc.TieredEvictionCoordinator;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L1HotStoreAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdmissionContentionBenchmarkRunner {

    static final String BENCHMARK_SCOPE = "Concurrent single-fragment L1 admission with isolated namespaces; "
            + "measures request latency, throughput, direct admission outcomes, lock acquisitions per request, "
            + "admission-lock wait/hold time, optimistic conflicts, and fallback rate; "
            + "keeps each round below namespace quota and 50% of global L1 capacity to avoid eviction and persistence I/O.";
    static final String SUCCESS_DEFINITION = "A round passes when every submitted fragment is admitted without task errors.";

    private final TieredEvictionCoordinator evictionCoordinator;
    private final L1HotStore l1;
    private final L1HotStoreAdmin l1Admin;
    private final NamespaceQuotaManager namespaceQuotaManager;
    private final MemorySloTracker sloTracker;
    private final LlmMemoryEvalProperties properties;

    public AdmissionContentionBenchmarkReport runConfiguredBenchmark() {
        List<Integer> parallelismLevels = sanitizeParallelismLevels(
                properties.getAdmissionBenchmarkParallelismLevels());
        int operationsPerThread = Math.max(1, properties.getAdmissionBenchmarkOperationsPerThread());
        int warmupOperationsPerThread = Math.max(0, properties.getAdmissionBenchmarkWarmupOperationsPerThread());
        int tokenCount = Math.max(1, properties.getAdmissionBenchmarkTokenCount());
        String runId = UUID.randomUUID().toString().substring(0, 8);

        List<AdmissionContentionBenchmarkReport.ParallelismResult> results = new ArrayList<>();
        for (int parallelism : parallelismLevels) {
            String namespace = "admission-contention-%s-p%d".formatted(runId, parallelism);
            validateRoundCapacity(namespace, parallelism, operationsPerThread, tokenCount);
            if (warmupOperationsPerThread > 0) {
                RoundResult warmup = runRound(
                        namespace + "-warmup",
                        parallelism,
                        warmupOperationsPerThread,
                        tokenCount);
                cleanup(warmup.admittedFragmentIds());
            }

            MemorySloTracker.AdmissionMetricsSnapshot before = sloTracker.admissionMetricsSnapshot();
            RoundResult measured = runRound(namespace, parallelism, operationsPerThread, tokenCount);
            MemorySloTracker.AdmissionMetricsSnapshot after = sloTracker.admissionMetricsSnapshot();
            results.add(toResult(parallelism, measured, before, after));
            cleanup(measured.admittedFragmentIds());
        }

        return AdmissionContentionBenchmarkReport.builder()
                .generatedAt(Instant.now())
                .runId(runId)
                .benchmarkScope(BENCHMARK_SCOPE)
                .successDefinition(SUCCESS_DEFINITION)
                .operationsPerThread(operationsPerThread)
                .warmupOperationsPerThread(warmupOperationsPerThread)
                .tokenCountPerFragment(tokenCount)
                .parallelismLevels(parallelismLevels)
                .results(List.copyOf(results))
                .build();
    }

    private RoundResult runRound(
            String namespace,
            int parallelism,
            int operationsPerThread,
            int tokenCount) {
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        CountDownLatch ready = new CountDownLatch(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> admittedFragmentIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        long startedNanos;
        try {
            for (int worker = 0; worker < parallelism; worker++) {
                int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    for (int operation = 0; operation < operationsPerThread; operation++) {
                        String fragmentId = "%s-w%02d-o%04d".formatted(
                                namespace,
                                workerIndex,
                                operation);
                        MemoryFragment fragment = benchmarkFragment(
                                fragmentId,
                                namespace,
                                tokenCount,
                                workerIndex,
                                operation);
                        long admissionStartedNanos = System.nanoTime();
                        try {
                            boolean admitted = evictionCoordinator.admitToL1(
                                    fragment,
                                    "admission-contention-benchmark");
                            latenciesNanos.add(System.nanoTime() - admissionStartedNanos);
                            if (admitted) {
                                admittedFragmentIds.add(fragmentId);
                            } else {
                                errors.add("Admission rejected fragmentId=" + fragmentId);
                            }
                        } catch (RuntimeException exception) {
                            latenciesNanos.add(System.nanoTime() - admissionStartedNanos);
                            errors.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
                        }
                    }
                }));
            }
            await(ready);
            startedNanos = System.nanoTime();
            start.countDown();
            long timeoutMillis = Math.max(
                    1L,
                    properties.getAdmissionBenchmarkTimeout().toMillis());
            for (Future<?> future : futures) {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (Exception exception) {
            start.countDown();
            errors.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
            throw new IllegalStateException("Admission contention benchmark round failed namespace=" + namespace, exception);
        } finally {
            executor.shutdownNow();
        }
        return new RoundResult(
                parallelism * operationsPerThread,
                List.copyOf(latenciesNanos),
                List.copyOf(admittedFragmentIds),
                List.copyOf(errors),
                System.nanoTime() - startedNanos);
    }

    private AdmissionContentionBenchmarkReport.ParallelismResult toResult(
            int parallelism,
            RoundResult round,
            MemorySloTracker.AdmissionMetricsSnapshot before,
            MemorySloTracker.AdmissionMetricsSnapshot after) {
        List<Long> sortedLatencies = round.latenciesNanos().stream().sorted().toList();
        long requests = delta(after.requestCount(), before.requestCount());
        long directAttempts = delta(after.directAttemptCount(), before.directAttemptCount());
        long directCommits = delta(after.directCommitCount(), before.directCommitCount());
        long directEscalations = delta(after.directEscalationCount(), before.directEscalationCount());
        long directRejections = delta(after.directRejectionCount(), before.directRejectionCount());
        long attempts = delta(after.optimisticAttemptCount(), before.optimisticAttemptCount());
        long commits = delta(after.optimisticCommitCount(), before.optimisticCommitCount());
        long conflicts = delta(after.optimisticConflictCount(), before.optimisticConflictCount());
        long fallbacks = delta(after.fallbackCount(), before.fallbackCount());
        long lockAcquisitions = delta(after.lockAcquisitionCount(), before.lockAcquisitionCount());
        long lockWaitNanos = delta(after.lockWaitNanosTotal(), before.lockWaitNanosTotal());
        long lockHoldNanos = delta(after.lockHoldNanosTotal(), before.lockHoldNanosTotal());
        long planningSamples = delta(after.planningCount(), before.planningCount());
        long planningNanos = delta(after.planningNanosTotal(), before.planningNanosTotal());
        int admitted = round.admittedFragmentIds().size();
        double elapsedSeconds = Math.max(1L, round.elapsedNanos()) / 1_000_000_000.0;

        return AdmissionContentionBenchmarkReport.ParallelismResult.builder()
                .parallelism(parallelism)
                .attempted(round.attempted())
                .admitted(admitted)
                .errors(round.errors().size())
                .successRate(ratio(admitted, round.attempted()))
                .elapsedMs(nanosToMillis(round.elapsedNanos()))
                .throughputPerSecond(admitted / elapsedSeconds)
                .latencyAverageMs(averageMillis(sortedLatencies))
                .latencyP50Ms(percentileMillis(sortedLatencies, 0.50))
                .latencyP95Ms(percentileMillis(sortedLatencies, 0.95))
                .latencyP99Ms(percentileMillis(sortedLatencies, 0.99))
                .admissionRequests(requests)
                .directAttempts(directAttempts)
                .directCommits(directCommits)
                .directEscalations(directEscalations)
                .directRejections(directRejections)
                .optimisticAttempts(attempts)
                .optimisticCommits(commits)
                .optimisticConflicts(conflicts)
                .fallbacks(fallbacks)
                .optimisticConflictRate(ratio(conflicts, attempts))
                .fallbackRate(ratio(fallbacks, requests))
                .lockAcquisitions(lockAcquisitions)
                .lockAcquisitionsPerRequest(ratio(lockAcquisitions, requests))
                .lockWaitAverageMs(averageMillis(lockWaitNanos, lockAcquisitions))
                .lockHoldAverageMs(averageMillis(lockHoldNanos, lockAcquisitions))
                .planningSamples(planningSamples)
                .planningAverageMs(averageMillis(planningNanos, planningSamples))
                .errorMessages(round.errors())
                .build();
    }

    private void validateRoundCapacity(
            String namespace,
            int parallelism,
            int operationsPerThread,
            int tokenCount) {
        long plannedTokens = Math.multiplyExact(
                Math.multiplyExact((long) parallelism, operationsPerThread),
                tokenCount);
        long capacity = l1.maxTokenCapacity();
        Collection<MemoryFragment> residents = l1Admin.allFragments();
        NamespaceQuotaManager.QuotaSnapshot quota = namespaceQuotaManager.snapshot(
                residents,
                capacity,
                namespace);
        long safeGlobalTokens = Math.max(0L, capacity / 2L - l1.currentTokenCount());
        long safeNamespaceTokens = Math.max(
                0L,
                quota.hardQuotaPerNamespace() - quota.focusNamespaceUsage());
        long safeTokens = Math.min(safeGlobalTokens, safeNamespaceTokens);
        if (plannedTokens > safeTokens) {
            throw new IllegalStateException(
                    "Admission benchmark round exceeds safe no-eviction budget parallelism=%d plannedTokens=%d safeTokens=%d capacity=%d hardQuota=%d"
                            .formatted(
                                    parallelism,
                                    plannedTokens,
                                    safeTokens,
                                    capacity,
                                    quota.hardQuotaPerNamespace()));
        }
    }

    private MemoryFragment benchmarkFragment(
            String fragmentId,
            String namespace,
            int tokenCount,
            int worker,
            int operation) {
        return MemoryFragment.builder()
                .id(fragmentId)
                .namespace(namespace)
                .content("Admission contention benchmark fragment worker=%d operation=%d"
                        .formatted(worker, operation))
                .embedding(new float[] {1.0f, 0.0f, 0.0f, 0.0f})
                .tokenCount(tokenCount)
                .importance(0.5d)
                .build();
    }

    private void cleanup(List<String> fragmentIds) {
        fragmentIds.forEach(fragmentId -> {
            try {
                evictionCoordinator.removeFromL1(fragmentId);
            } catch (RuntimeException exception) {
                log.warn("Failed to clean admission benchmark fragment fragmentId={}: {}",
                        fragmentId, exception.getMessage());
            }
        });
    }

    private List<Integer> sanitizeParallelismLevels(List<Integer> configured) {
        List<Integer> levels = configured == null ? List.of() : configured.stream()
                .filter(level -> level != null && level > 0)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return levels.isEmpty() ? List.of(1, 2, 4, 8) : levels;
    }

    private void await(CountDownLatch latch) {
        try {
            long timeoutMillis = Math.max(
                    1L,
                    properties.getAdmissionBenchmarkTimeout().toMillis());
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for admission benchmark workers");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating admission benchmark", interrupted);
        }
    }

    private long delta(long after, long before) {
        return Math.max(0L, after - before);
    }

    private double averageMillis(List<Long> nanos) {
        return nanos.isEmpty()
                ? 0.0
                : nanos.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;
    }

    private double averageMillis(long nanos, long count) {
        return count == 0L ? 0.0 : nanosToMillis(nanos) / count;
    }

    private double percentileMillis(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sortedNanos.size()) - 1;
        index = Math.max(0, Math.min(sortedNanos.size() - 1, index));
        return nanosToMillis(sortedNanos.get(index));
    }

    private double nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000.0;
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0 : numerator / (double) denominator;
    }

    private record RoundResult(
            int attempted,
            List<Long> latenciesNanos,
            List<String> admittedFragmentIds,
            List<String> errors,
            long elapsedNanos) {
    }
}
