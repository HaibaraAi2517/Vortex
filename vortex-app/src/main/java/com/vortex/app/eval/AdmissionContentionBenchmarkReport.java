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
public class AdmissionContentionBenchmarkReport {

    private Instant generatedAt;
    private String runId;
    private String benchmarkScope;
    private String successDefinition;
    private int operationsPerThread;
    private int warmupOperationsPerThread;
    private int tokenCountPerFragment;
    private List<Integer> parallelismLevels;
    private List<ParallelismResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParallelismResult {
        private int parallelism;
        private int attempted;
        private int admitted;
        private int errors;
        private double successRate;
        private double elapsedMs;
        private double throughputPerSecond;
        private double latencyAverageMs;
        private double latencyP50Ms;
        private double latencyP95Ms;
        private double latencyP99Ms;
        private long admissionRequests;
        private long directAttempts;
        private long directCommits;
        private long directEscalations;
        private long directRejections;
        private long optimisticAttempts;
        private long optimisticCommits;
        private long optimisticConflicts;
        private long fallbacks;
        private double optimisticConflictRate;
        private double fallbackRate;
        private long lockAcquisitions;
        private double lockAcquisitionsPerRequest;
        private double lockWaitAverageMs;
        private double lockHoldAverageMs;
        private long planningSamples;
        private double planningAverageMs;
        private List<String> errorMessages;
    }
}
