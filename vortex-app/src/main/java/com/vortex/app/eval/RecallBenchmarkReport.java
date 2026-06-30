package com.vortex.app.eval;

import com.vortex.common.dto.RecallDiagnostics;
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
public class RecallBenchmarkReport {

    private Instant generatedAt;
    private String runId;
    private String datasetLocation;
    private int totalCases;
    private int totalRuns;
    private int topK;
    private List<Integer> evaluationKs;
    private int tokenBudget;
    private List<String> modes;
    private List<CaseResult> results;
    private Map<String, ModeSummary> modeSummaries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModeSummary {
        private int total;
        private int errors;
        private double caseHitRate;
        private double allExpectedHitRate;
        private double recallAtK;
        private double precisionAtK;
        private double mrr;
        private double ndcg;
        private double averageLatencyMs;
        private Map<Integer, MetricAtK> metricsByK;
        private double recallAtKLiftVsVectorOnly;
        private double recallAtKRelativeLiftVsVectorOnly;
        private double caseHitRateLiftVsVectorOnly;
        private double caseHitRateRelativeLiftVsVectorOnly;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricAtK {
        private boolean recallHit;
        private boolean allExpectedReturned;
        private double recall;
        private double precision;
        private double reciprocalRank;
        private double ndcg;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseResult {
        private String caseId;
        private String mode;
        private String retrievalMode;
        private boolean rerankEnabled;
        private String namespace;
        private String question;
        private List<String> expectedFragmentIds;
        private List<String> returnedFragmentIds;
        private List<String> returnedTiers;
        private boolean recallHit;
        private boolean allExpectedReturned;
        private double recallAtK;
        private double precisionAtK;
        private double reciprocalRank;
        private double ndcg;
        private long latencyMs;
        private Map<Integer, MetricAtK> metricsByK;
        private RecallDiagnostics recallDiagnostics;
        private String errorMessage;
    }
}