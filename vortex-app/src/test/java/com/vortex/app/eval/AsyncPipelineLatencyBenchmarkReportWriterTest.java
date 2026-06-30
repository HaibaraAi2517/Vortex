package com.vortex.app.eval;

import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncPipelineLatencyBenchmarkReportWriterTest {

    @Test
    void writeShouldIncludeMainPathPipelineBackpressureAndResults(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReportOutputDir(tempDir.toString());
        AsyncPipelineLatencyBenchmarkReportWriter writer =
                new AsyncPipelineLatencyBenchmarkReportWriter(JsonMapperFactory.create(), properties);

        AsyncPipelineLatencyBenchmarkReport report = AsyncPipelineLatencyBenchmarkReport.builder()
                .generatedAt(Instant.parse("2026-06-28T01:02:03Z"))
                .runId("run12345")
                .randomSeed(20260629L)
                .mainPathScope("request -> hybrid retrieval -> rerank -> prompt/context assembly -> return payload")
                .asyncPipelineScope("background memory extraction + summary + semantic split + embedding + L2 index + L3 archive readiness")
                .successDefinition("main-path recall/rerank/prompt assembly plus L2/L3 readiness")
                .benchmarkScope("main path plus async readiness")
                .fragmentCount(2)
                .warmupFragmentCount(1)
                .modes(List.of("SYNC_BASELINE", "ASYNC_PIPELINE"))
                .syncAverageMainPathLatencyMs(40.0d)
                .asyncAverageMainPathLatencyMs(10.0d)
                .relativeMainPathLatencyReduction(0.75d)
                .persistenceSuccessRate(1.0d)
                .backpressureSummary(AsyncPipelineLatencyBenchmarkReport.BackpressureSummary.builder()
                        .policy("CALLER_RUNS")
                        .queueCapacity(4)
                        .queueSize(1)
                        .queueRemainingCapacity(3)
                        .activeWorkers(1)
                        .maxWorkers(2)
                        .callerRunsCount(2)
                        .callerRunsCountDuringBenchmark(1)
                        .maxObservedQueueSize(4)
                        .saturated(true)
                        .probeSubmittedCount(6)
                        .probeCompletedCount(6)
                        .probeSubmissionLatencyP95Ms(42.0d)
                        .probeSubmissionLatencyP99Ms(43.0d)
                        .probeReadinessLatencyP95Ms(120.0d)
                        .probeReadinessLatencyP99Ms(130.0d)
                        .build())
                .modeSummaries(Map.of(
                        "SYNC_BASELINE", summary(40.0d, 20.0d, 1.0d),
                        "ASYNC_PIPELINE", summary(10.0d, 45.0d, 1.0d)))
                .results(List.of(AsyncPipelineLatencyBenchmarkReport.CaseResult.builder()
                        .caseId("async-pipeline-001")
                        .mode("ASYNC_PIPELINE")
                        .pipelineId("pipeline-1")
                        .fragmentId("fragment-1")
                        .fragmentIds(List.of("fragment-1"))
                        .namespace("ns")
                        .pipelineStatus("COMPLETED")
                        .completedStages(List.of("EXTRACTION", "SUMMARY", "EMBEDDING", "L2_INDEX", "L3_ARCHIVE"))
                        .mainPathLatencyMs(10.0d)
                        .recallLatencyMs(4.0d)
                        .promptAssemblyLatencyMs(1.0d)
                        .memoryWriteSubmissionLatencyMs(0.5d)
                        .asyncPipelineLatencyMs(35.0d)
                        .readinessLatencyMs(45.0d)
                        .returnedFragmentCount(2)
                        .returnedTokenCount(50)
                        .promptTokenCount(120)
                        .includedPromptFragmentCount(2)
                        .recallCandidateCount(8)
                        .rerankCandidateCount(6)
                        .l1CandidateCount(3)
                        .l2SearchCandidateCount(2)
                        .keywordCandidateCount(3)
                        .mainPathSucceeded(true)
                        .recallSucceeded(true)
                        .promptAssemblySucceeded(true)
                        .l2Ready(true)
                        .l3Ready(true)
                        .persistenceSucceeded(true)
                        .queueSizeBefore(0)
                        .queueSizeAfter(1)
                        .queueCapacity(4)
                        .backpressurePolicy("CALLER_RUNS")
                        .build()))
                .build();

        AsyncPipelineLatencyBenchmarkReportWriter.WrittenReport written = writer.write(report);

        String markdown = Files.readString(written.markdownPath());
        String json = Files.readString(written.jsonPath());
        assertThat(markdown).contains("# Async Pipeline Latency Benchmark Report");
        assertThat(markdown).contains("RandomSeed: 20260629");
        assertThat(markdown).contains("MainPathScope: request -> hybrid retrieval -> rerank -> prompt/context assembly -> return payload");
        assertThat(markdown).contains("## Backpressure Summary");
        assertThat(markdown).contains("CALLER_RUNS");
        assertThat(markdown).contains("Probe Submit P95 (ms)");
        assertThat(markdown).contains("Probe Readiness P99 (ms)");
        assertThat(markdown).contains("Main P50 (ms) | Main P95 (ms) | Main P99 (ms)");
        assertThat(markdown).contains("Recall P95 (ms)");
        assertThat(markdown).contains("Pipeline TPS");
        assertThat(markdown).contains("async-pipeline-001 | ASYNC_PIPELINE | COMPLETED");
        assertThat(json).contains("\"runId\" : \"run12345\"");
        assertThat(json).contains("\"relativeMainPathLatencyReduction\" : 0.75");
        assertThat(json).contains("\"backpressureSummary\"");
        assertThat(json).contains("\"mainPathSucceeded\" : true");
    }

    private AsyncPipelineLatencyBenchmarkReport.ModeSummary summary(
            double mainAverage,
            double readinessAverage,
            double persistenceSuccessRate) {
        return AsyncPipelineLatencyBenchmarkReport.ModeSummary.builder()
                .total(1)
                .successes(1)
                .mainPathSuccesses(1)
                .recallSuccesses(1)
                .promptAssemblySuccesses(1)
                .l2ReadyCount(1)
                .l3ReadyCount(1)
                .extractionCompletedCount(1)
                .summaryCompletedCount(1)
                .embeddingCompletedCount(1)
                .l1AdmissionCompletedCount(1)
                .l2IndexCompletedCount(1)
                .l3ArchiveCompletedCount(1)
                .mainPathSuccessRate(1.0d)
                .persistenceSuccessRate(persistenceSuccessRate)
                .mainPathLatencyP50Ms(mainAverage)
                .mainPathLatencyP95Ms(mainAverage)
                .mainPathLatencyP99Ms(mainAverage)
                .mainPathLatencyAverageMs(mainAverage)
                .recallLatencyP95Ms(mainAverage / 2.0d)
                .promptAssemblyLatencyP95Ms(1.0d)
                .memoryWriteSubmissionLatencyP95Ms(2.0d)
                .asyncPipelineLatencyP95Ms(30.0d)
                .asyncPipelineLatencyAverageMs(25.0d)
                .asyncPipelineThroughputPerSecond(40.0d)
                .readinessLatencyP50Ms(readinessAverage)
                .readinessLatencyP95Ms(readinessAverage)
                .readinessLatencyP99Ms(readinessAverage)
                .readinessLatencyAverageMs(readinessAverage)
                .readinessLagAverageMs(Math.max(0.0d, readinessAverage - mainAverage))
                .returnedFragmentAverage(2.0d)
                .rerankCandidateAverage(6.0d)
                .build();
    }
}