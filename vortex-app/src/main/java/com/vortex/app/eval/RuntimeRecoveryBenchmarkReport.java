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
public class RuntimeRecoveryBenchmarkReport {

    private Instant generatedAt;
    private String runId;
    private int totalCases;
    private int passedCases;
    private int failedCases;
    private double successRate;
    private long totalLatencyMs;
    private double averageLatencyMs;
    private String successDefinition;
    private long randomSeed;
    private List<String> coveredCapabilities;
    private List<String> excludedCapabilities;
    private List<CategorySummary> categorySummaries;
    private List<CaseResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySummary {
        private String category;
        private int totalCases;
        private int passedCases;
        private int failedCases;
        private double successRate;
        private long totalLatencyMs;
        private double averageLatencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseResult {
        private String caseId;
        private String name;
        private String category;
        private String capability;
        private boolean passed;
        private String taskId;
        private String checkpointId;
        private long latencyMs;
        private String expected;
        private String actual;
        private Map<String, String> details;
        private String errorMessage;
    }
}
