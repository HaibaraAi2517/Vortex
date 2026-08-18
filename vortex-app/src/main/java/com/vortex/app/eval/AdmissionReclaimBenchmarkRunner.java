package com.vortex.app.eval;

import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.hmc.FragmentPersistenceManager;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.kernel.hmc.NamespaceQuotaManager;
import com.vortex.kernel.hmc.TieredEvictionCoordinator;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L1HotStoreAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AdmissionReclaimBenchmarkRunner {

    static final String BENCHMARK_SCOPE = "Full-capacity optimistic L1 admission with a large resident set; "
            + "singleton scenarios exercise concurrent one-fragment victims, while reasoning-chain scenarios "
            + "exercise atomic 20/50-member group eviction; reports detailed-snapshot lock hold, lock-free "
            + "snapshot freezing, namespace planning-gate wait, planning, optimistic commit lock hold, "
            + "conflicts, fallback, and request latency.";
    static final String SUCCESS_DEFINITION = "Every replacement is admitted, exactly the expected victim fragments "
            + "are removed, no task errors occur, and L1 remains within its token capacity.";
    private static final int MIN_EMBEDDING_DIMENSIONS = 2;
    private static final long COLD_LAST_ACCESS_TIME = 1L;

    private final TieredEvictionCoordinator evictionCoordinator;
    private final L1HotStore l1;
    private final L1HotStoreAdmin l1Admin;
    private final NamespaceQuotaManager namespaceQuotaManager;
    private final MemorySloTracker sloTracker;
    private final FragmentPersistenceManager persistenceManager;
    private final LlmMemoryEvalProperties properties;

    public AdmissionReclaimBenchmarkReport runConfiguredBenchmark() {
        ensureEmptyL1();
        String runId = UUID.randomUUID().toString().substring(0, 8);
        int residentTarget = Math.max(1, properties.getReclaimBenchmarkResidentFragments());
        int embeddingDimensions = Math.max(
                MIN_EMBEDDING_DIMENSIONS,
                properties.getReclaimBenchmarkEmbeddingDimensions());
        int singletonOperations = Math.max(
                1,
                properties.getReclaimBenchmarkSingletonOperationsPerThread());
        int chainOperations = Math.max(
                1,
                properties.getReclaimBenchmarkChainOperationsPerThread());
        int warmupOperations = Math.max(
                0,
                properties.getReclaimBenchmarkWarmupOperationsPerThread());

        List<ScenarioConfig> scenarios = new ArrayList<>();
        sanitizePositive(properties.getReclaimBenchmarkSingletonParallelismLevels(), List.of(1, 8))
                .forEach(parallelism -> scenarios.add(new ScenarioConfig(
                        "singleton-p" + parallelism,
                        1,
                        parallelism,
                        singletonOperations)));
        sanitizePositive(properties.getReclaimBenchmarkReasoningChainSizes(), List.of(20, 50))
                .forEach(chainSize -> scenarios.add(new ScenarioConfig(
                        "reasoning-chain-" + chainSize,
                        chainSize,
                        1,
                        chainOperations)));

        List<AdmissionReclaimBenchmarkReport.ScenarioResult> results = new ArrayList<>();
        for (ScenarioConfig scenario : scenarios) {
            results.add(runScenario(
                    runId,
                    scenario,
                    residentTarget,
                    embeddingDimensions,
                    warmupOperations));
        }
        ensureEmptyL1();

        return AdmissionReclaimBenchmarkReport.builder()
                .generatedAt(Instant.now())
                .runId(runId)
                .benchmarkScope(BENCHMARK_SCOPE)
                .successDefinition(SUCCESS_DEFINITION)
                .residentFragmentTarget(residentTarget)
                .embeddingDimensions(embeddingDimensions)
                .warmupOperationsPerThread(warmupOperations)
                .results(List.copyOf(results))
                .build();
    }

    private AdmissionReclaimBenchmarkReport.ScenarioResult runScenario(
            String runId,
            ScenarioConfig scenario,
            int residentTarget,
            int embeddingDimensions,
            int warmupOperations) {
        if (warmupOperations > 0) {
            ScenarioConfig warmupScenario = new ScenarioConfig(
                    scenario.name() + "-warmup",
                    scenario.victimGroupSize(),
                    scenario.parallelism(),
                    warmupOperations);
            RoundFixture warmupFixture = prepareRound(
                    runId,
                    warmupScenario,
                    residentTarget,
                    embeddingDimensions);
            try {
                validateWarmup(warmupScenario, warmupFixture, runRound(warmupFixture, warmupScenario));
            } finally {
                cleanup(warmupFixture.namespaces());
            }
            awaitPersistenceDrain(warmupScenario.name());
        }
        RoundFixture fixture = prepareRound(runId, scenario, residentTarget, embeddingDimensions);
        AdmissionReclaimBenchmarkReport.ScenarioResult result;
        try {
            MemorySloTracker.AdmissionMetricsSnapshot before = sloTracker.admissionMetricsSnapshot();
            RoundResult round = runRound(fixture, scenario);
            MemorySloTracker.AdmissionMetricsSnapshot after = sloTracker.admissionMetricsSnapshot();
            result = toResult(scenario, fixture, round, before, after);
        } finally {
            cleanup(fixture.namespaces());
        }
        awaitPersistenceDrain(scenario.name());
        return result;
    }

    private void validateWarmup(
            ScenarioConfig scenario,
            RoundFixture fixture,
            RoundResult round) {
        int expectedEvicted = round.attempted() * scenario.victimGroupSize();
        if (!round.errors().isEmpty()
                || round.admitted() != round.attempted()
                || round.actualEvicted() != expectedEvicted
                || l1.currentTokenCount() > fixture.tokenCapacity()) {
            throw new IllegalStateException(
                    "Admission reclaim benchmark warmup failed scenario=%s admitted=%d/%d "
                            + "evicted=%d/%d errors=%s"
                            .formatted(
                                    scenario.name(),
                                    round.admitted(),
                                    round.attempted(),
                                    round.actualEvicted(),
                                    expectedEvicted,
                                    round.errors()));
        }
    }

    private void awaitPersistenceDrain(String scenario) {
        if (!persistenceManager.awaitQuiescence(properties.getReclaimBenchmarkTimeout())) {
            throw new IllegalStateException(
                    "Timed out draining reclaim benchmark persistence scenario=" + scenario);
        }
    }

    private RoundFixture prepareRound(
            String runId,
            ScenarioConfig scenario,
            int configuredResidentTarget,
            int embeddingDimensions) {
        ensureEmptyL1();
        long capacity = l1.maxTokenCapacity();
        int namespaceCount = Math.max(1, properties.getReclaimBenchmarkNamespaceCount());
        if (capacity <= 0L || capacity > Integer.MAX_VALUE) {
            throw new IllegalStateException("Reclaim benchmark requires L1 capacity in range 1.."
                    + Integer.MAX_VALUE + ", actual=" + capacity);
        }
        long hardQuota = namespaceQuotaManager.hardQuotaPerNamespace(capacity, namespaceCount);
        if (Math.multiplyExact(hardQuota, namespaceCount) != capacity) {
            throw new IllegalStateException(
                    "Reclaim benchmark requires namespace hard quotas to exactly cover L1 capacity "
                            + "capacity=%d namespaces=%d hardQuota=%d"
                            .formatted(capacity, namespaceCount, hardQuota));
        }

        int attempted = Math.multiplyExact(scenario.parallelism(), scenario.operationsPerThread());
        int[] operationsByNamespace = new int[namespaceCount];
        for (int operation = 0; operation < attempted; operation++) {
            operationsByNamespace[operation % namespaceCount]++;
        }
        int[] requiredFragments = new int[namespaceCount];
        int minimumResidents = namespaceCount;
        for (int namespaceIndex = 0; namespaceIndex < namespaceCount; namespaceIndex++) {
            requiredFragments[namespaceIndex] = Math.multiplyExact(
                    operationsByNamespace[namespaceIndex],
                    scenario.victimGroupSize() + 1);
            if (requiredFragments[namespaceIndex] + 1 > hardQuota) {
                throw new IllegalStateException(
                        "Reclaim benchmark scenario does not fit namespace quota scenario=%s namespace=%d "
                                + "requiredFragments=%d hardQuota=%d"
                                .formatted(
                                        scenario.name(),
                                        namespaceIndex,
                                        requiredFragments[namespaceIndex] + 1,
                                        hardQuota));
            }
            minimumResidents = Math.addExact(minimumResidents, requiredFragments[namespaceIndex]);
        }
        int residentTarget = Math.max(configuredResidentTarget, minimumResidents);
        if (residentTarget > capacity) {
            throw new IllegalStateException(
                    "Reclaim benchmark resident target exceeds one-token capacity scenario=%s "
                            + "residentTarget=%d capacity=%d"
                            .formatted(scenario.name(), residentTarget, capacity));
        }

        int[] fillerCounts = new int[namespaceCount];
        java.util.Arrays.fill(fillerCounts, 1);
        int remainingResidentSlots = residentTarget - minimumResidents;
        int cursor = 0;
        while (remainingResidentSlots > 0) {
            int namespaceIndex = cursor++ % namespaceCount;
            if ((long) requiredFragments[namespaceIndex] + fillerCounts[namespaceIndex] >= hardQuota) {
                continue;
            }
            fillerCounts[namespaceIndex]++;
            remainingResidentSlots--;
        }

        List<String> namespaces = new ArrayList<>(namespaceCount);
        for (int namespaceIndex = 0; namespaceIndex < namespaceCount; namespaceIndex++) {
            namespaces.add("admission-reclaim-%s-%s-n%d"
                    .formatted(runId, scenario.name(), namespaceIndex));
        }
        try {
            List<Operation> operations = new ArrayList<>(attempted);
            List<String> victimIds = new ArrayList<>(attempted * scenario.victimGroupSize());
            int[] operationSequence = new int[namespaceCount];
            float[] retainedEmbedding = basisVector(embeddingDimensions, 0);
            float[] victimEmbedding = basisVector(embeddingDimensions, 1);

            for (int operation = 0; operation < attempted; operation++) {
                int namespaceIndex = operation % namespaceCount;
                int sequence = operationSequence[namespaceIndex]++;
                String namespace = namespaces.get(namespaceIndex);
                String operationId = "%s-o%04d".formatted(namespace, sequence);
                String placeholderId = operationId + "-incoming";
                l1.put(fragment(
                        placeholderId,
                        namespace,
                        "reclaim benchmark placeholder",
                        retainedEmbedding,
                        1,
                        1.0d,
                        System.currentTimeMillis(),
                        null), false);

                String reasoningChainId = scenario.victimGroupSize() == 1
                        ? null
                        : operationId + "-chain";
                for (int victim = 0; victim < scenario.victimGroupSize(); victim++) {
                    String victimId = operationId + "-victim-%03d".formatted(victim);
                    l1.put(fragment(
                            victimId,
                            namespace,
                            "reclaim benchmark victim",
                            victimEmbedding,
                            1,
                            0.0d,
                            COLD_LAST_ACCESS_TIME,
                            reasoningChainId), false);
                    victimIds.add(victimId);
                }
                operations.add(new Operation(
                        placeholderId,
                        namespace,
                        scenario.victimGroupSize() + 1,
                        retainedEmbedding));
            }

            for (int namespaceIndex = 0; namespaceIndex < namespaceCount; namespaceIndex++) {
                String namespace = namespaces.get(namespaceIndex);
                long fillerTokens = hardQuota - requiredFragments[namespaceIndex];
                int fillerCount = fillerCounts[namespaceIndex];
                long baseTokens = fillerTokens / fillerCount;
                int extraTokens = (int) (fillerTokens % fillerCount);
                for (int filler = 0; filler < fillerCount; filler++) {
                    int tokenCount = Math.toIntExact(baseTokens + (filler < extraTokens ? 1L : 0L));
                    l1.put(fragment(
                            "%s-filler-%04d".formatted(namespace, filler),
                            namespace,
                            "reclaim benchmark retained filler",
                            retainedEmbedding,
                            tokenCount,
                            1.0d,
                            System.currentTimeMillis(),
                            null), false);
                }
            }
            evictionCoordinator.rebalanceTierIndexes();

            int residentFragmentsBefore = l1Admin.allFragments().size();
            if (residentFragmentsBefore != residentTarget || l1.currentTokenCount() != capacity) {
                throw new IllegalStateException(
                        "Reclaim benchmark setup invariant failed scenario=%s residents=%d/%d tokens=%d/%d"
                                .formatted(
                                        scenario.name(),
                                        residentFragmentsBefore,
                                        residentTarget,
                                        l1.currentTokenCount(),
                                        capacity));
            }
            return new RoundFixture(
                    List.copyOf(namespaces),
                    List.copyOf(operations),
                    List.copyOf(victimIds),
                    residentFragmentsBefore,
                    capacity);
        } catch (RuntimeException | Error failure) {
            cleanup(namespaces);
            throw failure;
        }
    }

    private RoundResult runRound(RoundFixture fixture, ScenarioConfig scenario) {
        ExecutorService executor = Executors.newFixedThreadPool(scenario.parallelism());
        CountDownLatch ready = new CountDownLatch(scenario.parallelism());
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        long startedNanos;
        try {
            for (int worker = 0; worker < scenario.parallelism(); worker++) {
                int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    for (int operation = 0; operation < scenario.operationsPerThread(); operation++) {
                        Operation planned = fixture.operations().get(
                                workerIndex * scenario.operationsPerThread() + operation);
                        MemoryFragment incoming = fragment(
                                planned.fragmentId(),
                                planned.namespace(),
                                "reclaim benchmark admitted replacement",
                                planned.embedding(),
                                planned.tokenCount(),
                                1.0d,
                                System.currentTimeMillis(),
                                null);
                        long admissionStartedNanos = System.nanoTime();
                        try {
                            boolean admitted = evictionCoordinator.admitToL1(
                                    incoming,
                                    "admission-reclaim-benchmark");
                            latenciesNanos.add(System.nanoTime() - admissionStartedNanos);
                            if (!admitted) {
                                errors.add("Admission rejected fragmentId=" + incoming.getId());
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
                    properties.getReclaimBenchmarkTimeout().toMillis());
            for (Future<?> future : futures) {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (Exception exception) {
            start.countDown();
            throw new IllegalStateException(
                    "Admission reclaim benchmark round failed scenario=" + scenario.name(),
                    exception);
        } finally {
            executor.shutdownNow();
        }
        int actualEvicted = (int) fixture.victimIds().stream()
                .filter(victimId -> l1.peek(victimId).isEmpty())
                .count();
        int admitted = (int) fixture.operations().stream()
                .filter(operation -> l1.peek(operation.fragmentId())
                        .map(fragment -> fragment.getTokenCount() == operation.tokenCount())
                        .orElse(false))
                .count();
        return new RoundResult(
                scenario.parallelism() * scenario.operationsPerThread(),
                admitted,
                actualEvicted,
                List.copyOf(latenciesNanos),
                List.copyOf(errors),
                System.nanoTime() - startedNanos);
    }

    private AdmissionReclaimBenchmarkReport.ScenarioResult toResult(
            ScenarioConfig scenario,
            RoundFixture fixture,
            RoundResult round,
            MemorySloTracker.AdmissionMetricsSnapshot before,
            MemorySloTracker.AdmissionMetricsSnapshot after) {
        List<Long> sortedLatencies = round.latenciesNanos().stream().sorted().toList();
        long requests = delta(after.requestCount(), before.requestCount());
        long planningGateWaits = delta(
                after.planningGateWaitCount(),
                before.planningGateWaitCount());
        long planningGateWaitNanos = delta(
                after.planningGateWaitNanosTotal(),
                before.planningGateWaitNanosTotal());
        long lockAcquisitions = delta(after.lockAcquisitionCount(), before.lockAcquisitionCount());
        long detailedSnapshots = delta(after.detailedSnapshotCount(), before.detailedSnapshotCount());
        long detailedSnapshotHoldNanos = delta(
                after.detailedSnapshotLockHoldNanosTotal(),
                before.detailedSnapshotLockHoldNanosTotal());
        long detailedSnapshotFreezes = delta(
                after.detailedSnapshotFreezeCount(),
                before.detailedSnapshotFreezeCount());
        long detailedSnapshotFreezeNanos = delta(
                after.detailedSnapshotFreezeNanosTotal(),
                before.detailedSnapshotFreezeNanosTotal());
        long planningSamples = delta(after.planningCount(), before.planningCount());
        long commitLocks = delta(after.commitLockCount(), before.commitLockCount());
        long conflicts = delta(after.optimisticConflictCount(), before.optimisticConflictCount());
        long fallbacks = delta(after.fallbackCount(), before.fallbackCount());
        int expectedEvicted = round.attempted() * scenario.victimGroupSize();
        long tokenCountAfter = l1.currentTokenCount();
        boolean capacityInvariant = tokenCountAfter <= fixture.tokenCapacity();
        List<String> errors = new ArrayList<>(round.errors());
        if (round.actualEvicted() != expectedEvicted) {
            errors.add("Expected evicted fragments=%d actual=%d"
                    .formatted(expectedEvicted, round.actualEvicted()));
        }
        if (!capacityInvariant) {
            errors.add("L1 capacity exceeded tokens=%d capacity=%d"
                    .formatted(tokenCountAfter, fixture.tokenCapacity()));
        }
        double elapsedSeconds = Math.max(1L, round.elapsedNanos()) / 1_000_000_000.0;

        return AdmissionReclaimBenchmarkReport.ScenarioResult.builder()
                .scenario(scenario.name())
                .victimGroupSize(scenario.victimGroupSize())
                .parallelism(scenario.parallelism())
                .operationsPerThread(scenario.operationsPerThread())
                .residentFragmentsBefore(fixture.residentFragmentsBefore())
                .tokenCapacity(fixture.tokenCapacity())
                .attempted(round.attempted())
                .admitted(round.admitted())
                .errors(errors.size())
                .expectedEvictedFragments(expectedEvicted)
                .actualEvictedFragments(round.actualEvicted())
                .tokenCountAfter(tokenCountAfter)
                .capacityInvariantSatisfied(capacityInvariant)
                .elapsedMs(nanosToMillis(round.elapsedNanos()))
                .throughputPerSecond(round.admitted() / elapsedSeconds)
                .latencyAverageMs(averageMillis(sortedLatencies))
                .latencyP50Ms(percentileMillis(sortedLatencies, 0.50))
                .latencyP95Ms(percentileMillis(sortedLatencies, 0.95))
                .latencyP99Ms(percentileMillis(sortedLatencies, 0.99))
                .admissionRequests(requests)
                .planningGateWaitCount(planningGateWaits)
                .planningGateWaitTotalMs(nanosToMillis(planningGateWaitNanos))
                .planningGateWaitAverageMs(averageMillis(
                        planningGateWaitNanos,
                        planningGateWaits))
                .lockAcquisitions(lockAcquisitions)
                .lockAcquisitionsPerRequest(ratio(lockAcquisitions, requests))
                .lockWaitAverageMs(averageMillis(
                        delta(after.lockWaitNanosTotal(), before.lockWaitNanosTotal()),
                        lockAcquisitions))
                .lockHoldAverageMs(averageMillis(
                        delta(after.lockHoldNanosTotal(), before.lockHoldNanosTotal()),
                        lockAcquisitions))
                .detailedSnapshotCount(detailedSnapshots)
                .detailedSnapshotLockHoldAverageMs(averageMillis(
                        detailedSnapshotHoldNanos,
                        detailedSnapshots))
                .detailedSnapshotFreezeAverageMs(averageMillis(
                        detailedSnapshotFreezeNanos,
                        detailedSnapshotFreezes))
                .planningSamples(planningSamples)
                .planningAverageMs(averageMillis(
                        delta(after.planningNanosTotal(), before.planningNanosTotal()),
                        planningSamples))
                .commitLockCount(commitLocks)
                .commitLockHoldAverageMs(averageMillis(
                        delta(after.commitLockHoldNanosTotal(), before.commitLockHoldNanosTotal()),
                        commitLocks))
                .optimisticConflicts(conflicts)
                .optimisticConflictRate(ratio(
                        conflicts,
                        delta(after.optimisticAttemptCount(), before.optimisticAttemptCount())))
                .fallbacks(fallbacks)
                .fallbackRate(ratio(fallbacks, requests))
                .errorMessages(List.copyOf(errors))
                .build();
    }

    private MemoryFragment fragment(
            String id,
            String namespace,
            String content,
            float[] embedding,
            int tokenCount,
            double importance,
            long lastAccessTime,
            String reasoningChainId) {
        return MemoryFragment.builder()
                .id(id)
                .namespace(namespace)
                .content(content)
                .embedding(embedding.clone())
                .tokenCount(tokenCount)
                .importance(importance)
                .lastAccessTime(lastAccessTime)
                .reasoningChainId(reasoningChainId)
                .build();
    }

    private float[] basisVector(int dimensions, int activeDimension) {
        float[] vector = new float[dimensions];
        vector[activeDimension] = 1.0f;
        return vector;
    }

    private void cleanup(List<String> namespaces) {
        namespaces.forEach(l1::clear);
        evictionCoordinator.rebalanceTierIndexes();
    }

    private void ensureEmptyL1() {
        if (!l1Admin.allFragments().isEmpty() || l1.currentTokenCount() != 0L) {
            throw new IllegalStateException(
                    "Admission reclaim benchmark requires an empty L1 and will not clear pre-existing data");
        }
    }

    private List<Integer> sanitizePositive(List<Integer> configured, List<Integer> defaults) {
        List<Integer> values = configured == null ? List.of() : configured.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return values.isEmpty() ? defaults : values;
    }

    private void await(CountDownLatch latch) {
        try {
            long timeoutMillis = Math.max(
                    1L,
                    properties.getReclaimBenchmarkTimeout().toMillis());
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for reclaim benchmark workers");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while coordinating reclaim benchmark",
                    interrupted);
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

    private record ScenarioConfig(
            String name,
            int victimGroupSize,
            int parallelism,
            int operationsPerThread) {
    }

    private record Operation(
            String fragmentId,
            String namespace,
            int tokenCount,
            float[] embedding) {
    }

    private record RoundFixture(
            List<String> namespaces,
            List<Operation> operations,
            List<String> victimIds,
            int residentFragmentsBefore,
            long tokenCapacity) {
    }

    private record RoundResult(
            int attempted,
            int admitted,
            int actualEvicted,
            List<Long> latenciesNanos,
            List<String> errors,
            long elapsedNanos) {
    }
}
