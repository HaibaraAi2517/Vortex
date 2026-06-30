package com.vortex.app.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMemoryEvalReport {

    private Instant generatedAt;
    private int totalCases;
    private int totalRuns;
    private List<LlmMemoryEvalResult> results;
    private Map<String, ModeSummary> modeSummaries;
    private LlmMemoryEvalEnvironmentSnapshot environment;
    private RuntimeTelemetry runtimeTelemetry;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModeSummary {
        private int total;
        private int correct;
        private double accuracy;
        private double recallHitRate;
        private double recallHitRateLiftVsVectorOnly;
        private double recallHitRateRelativeLiftVsVectorOnly;
        private double averageLatencyMs;
        private int recoveredRuns;
        private double recoveredAccuracy;
        private double recoveredL2HitRate;
        private int feedbackSubmitted;
        private long learningSampleCountDelta;
        private long learningUpdateCountDelta;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuntimeTelemetry {
        private int configuredParallelism;
        private int actualWorkerCount;
        private boolean modePhasedParallel;
        private long totalElapsedMs;
        private List<ModePhaseTiming> modePhaseTimings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModePhaseTiming {
        private int modeIndex;
        private String mode;
        private int caseCount;
        private long elapsedMs;
    }
}
