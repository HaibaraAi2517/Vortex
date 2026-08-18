package com.vortex.app.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionReclaimBenchmarkReport {

    private Instant generatedAt;
    private String runId;
    private String benchmarkScope;
    private String successDefinition;
    private int residentFragmentTarget;
    private int embeddingDimensions;
    private int warmupOperationsPerThread;
    private boolean persistenceDrained;
    private List<ScenarioResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioResult {
        private String scenario;
        private int victimGroupSize;
        private int parallelism;
        private int operationsPerThread;
        private int residentFragmentsBefore;
        private long tokenCapacity;
        private int attempted;
        private int admitted;
        private int errors;
        private int expectedEvictedFragments;
        private int actualEvictedFragments;
        private long tokenCountAfter;
        private boolean capacityInvariantSatisfied;
        private double elapsedMs;
        private double throughputPerSecond;
        private double latencyAverageMs;
        private double latencyP50Ms;
        private double latencyP95Ms;
        private double latencyP99Ms;
        private long admissionRequests;
        private long planningGateWaitCount;
        private double planningGateWaitTotalMs;
        private double planningGateWaitAverageMs;
        private long lockAcquisitions;
        private double lockAcquisitionsPerRequest;
        private double lockWaitAverageMs;
        private double lockHoldAverageMs;
        private long detailedSnapshotCount;
        private double detailedSnapshotLockHoldAverageMs;
        private double detailedSnapshotFreezeAverageMs;
        private long planningSamples;
        private double planningAverageMs;
        private long commitLockCount;
        private double commitLockHoldAverageMs;
        private long optimisticConflicts;
        private double optimisticConflictRate;
        private long fallbacks;
        private double fallbackRate;
        private List<String> errorMessages;
    }
}
