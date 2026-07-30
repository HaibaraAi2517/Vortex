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
public class AsyncPipelineLatencyBenchmarkReport {

    private Instant generatedAt;
    private String runId;
    private long randomSeed;
    private String mainPathScope;
    private String asyncPipelineScope;
    private String successDefinition;
    private String benchmarkScope;
    private int fragmentCount;
    private int warmupFragmentCount;
    private List<String> modes;
    private double syncAverageMainPathLatencyMs;
    private double asyncAverageMainPathLatencyMs;
    private double relativeMainPathLatencyReduction;
    private double persistenceSuccessRate;
    private BackpressureSummary backpressureSummary;
    private Map<String, ModeSummary> modeSummaries;
    private List<CaseResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModeSummary {
        private int total;
        private int successes;
        private int mainPathSuccesses;
        private int writeThroughVisibleCount;
        private int recallSuccesses;
        private int promptAssemblySuccesses;
        private int errors;
        private int l2ReadyCount;
        private int l3ReadyCount;
        private int extractionCompletedCount;
        private int summaryCompletedCount;
        private int embeddingCompletedCount;
        private int l1AdmissionCompletedCount;
        private int l2IndexCompletedCount;
        private int l3ArchiveCompletedCount;
        private double mainPathSuccessRate;
        private double writeThroughVisibilityRate;
        private double persistenceSuccessRate;
        private double mainPathLatencyP50Ms;
        private double mainPathLatencyP95Ms;
        private double mainPathLatencyP99Ms;
        private double mainPathLatencyAverageMs;
        private double mainPathLatencyMinMs;
        private double mainPathLatencyMaxMs;
        private double recallLatencyP50Ms;
        private double recallLatencyP95Ms;
        private double recallLatencyP99Ms;
        private double recallLatencyAverageMs;
        private double promptAssemblyLatencyP50Ms;
        private double promptAssemblyLatencyP95Ms;
        private double promptAssemblyLatencyP99Ms;
        private double promptAssemblyLatencyAverageMs;
        private double memoryWriteSubmissionLatencyP50Ms;
        private double memoryWriteSubmissionLatencyP95Ms;
        private double memoryWriteSubmissionLatencyP99Ms;
        private double memoryWriteSubmissionLatencyAverageMs;
        private double asyncPipelineLatencyP50Ms;
        private double asyncPipelineLatencyP95Ms;
        private double asyncPipelineLatencyP99Ms;
        private double asyncPipelineLatencyAverageMs;
        private double asyncPipelineThroughputPerSecond;
        private double readinessLatencyP50Ms;
        private double readinessLatencyP95Ms;
        private double readinessLatencyP99Ms;
        private double readinessLatencyAverageMs;
        private double readinessLatencyMinMs;
        private double readinessLatencyMaxMs;
        private double readinessLagAverageMs;
        private double returnedFragmentAverage;
        private double rerankCandidateAverage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BackpressureSummary {
        private String policy;
        private int queueCapacity;
        private int queueSize;
        private int queueRemainingCapacity;
        private int activeWorkers;
        private int maxWorkers;
        private long callerRunsCount;
        private long callerRunsCountDuringBenchmark;
        private long maxObservedQueueSize;
        private boolean saturated;
        private int probeSubmittedCount;
        private int probeCompletedCount;
        private int probeErrorCount;
        private double probeSubmissionLatencyP50Ms;
        private double probeSubmissionLatencyP95Ms;
        private double probeSubmissionLatencyP99Ms;
        private double probeSubmissionLatencyAverageMs;
        private double probeReadinessLatencyP50Ms;
        private double probeReadinessLatencyP95Ms;
        private double probeReadinessLatencyP99Ms;
        private double probeReadinessLatencyAverageMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseResult {
        private String caseId;
        private String mode;
        private String pipelineId;
        private String fragmentId;
        private List<String> fragmentIds;
        private String namespace;
        private String pipelineStatus;
        private List<String> completedStages;
        private int extractedUnitCount;
        private int summaryTokenCount;
        private int fragmentCount;
        private double mainPathLatencyMs;
        private double recallLatencyMs;
        private double promptAssemblyLatencyMs;
        private double memoryWriteSubmissionLatencyMs;
        private double asyncPipelineLatencyMs;
        private double readinessLatencyMs;
        private int returnedFragmentCount;
        private int returnedTokenCount;
        private int promptTokenCount;
        private int includedPromptFragmentCount;
        private int omittedPromptFragmentCount;
        private int recallCandidateCount;
        private int rerankCandidateCount;
        private int l1CandidateCount;
        private int l2SearchCandidateCount;
        private int keywordCandidateCount;
        private boolean recallSucceeded;
        private boolean promptAssemblySucceeded;
        private boolean mainPathSucceeded;
        private boolean writeThroughVisibleAtReturn;
        private boolean l2Ready;
        private boolean l3Ready;
        private boolean persistenceSucceeded;
        private int queueSizeBefore;
        private int queueSizeAfter;
        private int queueCapacity;
        private int queueRemainingCapacity;
        private long callerRunsCountBefore;
        private long callerRunsCountAfter;
        private String backpressurePolicy;
        private String errorMessage;
    }
}
