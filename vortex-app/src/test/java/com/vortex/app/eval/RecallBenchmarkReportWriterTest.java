package com.vortex.app.eval;

import com.vortex.common.dto.RecallDiagnostics;
import com.vortex.common.dto.RerankEffectStatus;
import com.vortex.common.dto.RerankerType;
import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecallBenchmarkReportWriterTest {

    @Test
    void writeShouldIncludeSummaryResultsDiagnosticsAndMetricsByK(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReportOutputDir(tempDir.toString());
        RecallBenchmarkReportWriter writer =
                new RecallBenchmarkReportWriter(JsonMapperFactory.create(), properties);
        Map<Integer, RecallBenchmarkReport.MetricAtK> metricsByK = Map.of(
                1, RecallBenchmarkReport.MetricAtK.builder()
                        .recallHit(true)
                        .allExpectedReturned(true)
                        .recall(1.0d)
                        .precision(1.0d)
                        .reciprocalRank(1.0d)
                        .ndcg(1.0d)
                        .build(),
                3, RecallBenchmarkReport.MetricAtK.builder()
                        .recallHit(true)
                        .allExpectedReturned(true)
                        .recall(1.0d)
                        .precision(0.5d)
                        .reciprocalRank(1.0d)
                        .ndcg(1.0d)
                        .build());

        RecallBenchmarkReport report = RecallBenchmarkReport.builder()
                .generatedAt(Instant.parse("2026-06-25T01:02:03Z"))
                .runId("abc12345")
                .datasetLocation("classpath:llm-memory-eval-set-v2-1-extended.json")
                .totalCases(1)
                .totalRuns(2)
                .topK(3)
                .evaluationKs(List.of(1, 3))
                .tokenBudget(512)
                .modes(List.of("Vector+Rerank", "Hybrid+Rerank"))
                .modeSummaries(Map.of(
                        "Vector+Rerank", RecallBenchmarkReport.ModeSummary.builder()
                                .total(1)
                                .caseHitRate(0.0d)
                                .recallAtK(0.0d)
                                .metricsByK(Map.of(1, RecallBenchmarkReport.MetricAtK.builder().build()))
                                .build(),
                        "Hybrid+Rerank", RecallBenchmarkReport.ModeSummary.builder()
                                .total(1)
                                .caseHitRate(1.0d)
                                .recallAtK(1.0d)
                                .precisionAtK(0.5d)
                                .mrr(1.0d)
                                .ndcg(1.0d)
                                .averageLatencyMs(12.0d)
                                .latencyP50Ms(12.0d)
                                .latencyP95Ms(12.0d)
                                .latencyP99Ms(12.0d)
                                .metricsByK(metricsByK)
                                .recallAtKLiftVsVectorOnly(1.0d)
                                .caseHitRateLiftVsVectorOnly(1.0d)
                                .build()))
                .results(List.of(RecallBenchmarkReport.CaseResult.builder()
                        .caseId("keyword-lift-001")
                        .mode("Hybrid+Rerank")
                        .retrievalMode("HYBRID")
                        .rerankEnabled(true)
                        .rerankerType(RerankerType.LINEAR_SCORE_FUSION)
                        .expectedFragmentIds(List.of("exact-owner"))
                        .returnedFragmentIds(List.of("exact-owner", "distractor"))
                        .returnedTiers(List.of("L2", "L2"))
                        .recallHit(true)
                        .allExpectedReturned(true)
                        .recallAtK(1.0d)
                        .precisionAtK(0.5d)
                        .reciprocalRank(1.0d)
                        .ndcg(1.0d)
                        .metricsByK(metricsByK)
                        .latencyMs(12L)
                        .recallDiagnostics(RecallDiagnostics.builder()
                                .retrievalMode("HYBRID")
                                .rerankEnabled(true)
                                .keywordCandidateCount(2)
                                .keywordAcceptedCount(1)
                                .vectorCandidateCount(2)
                                .vectorAcceptedCount(2)
                                .rerankCandidateCount(2)
                                .rerankInputCandidateCount(2)
                                .rerankOutputCandidateCount(2)
                                .rerankChangedPositionCount(2)
                                .rerankTopKMembershipChangedCount(2)
                                .semanticScoreDistinctCount(2)
                                .keywordScoreDistinctCount(2)
                                .importanceDistinctCount(1)
                                .rerankerType(RerankerType.LINEAR_SCORE_FUSION)
                                .rerankEffectStatus(RerankEffectStatus.ORDER_CHANGED)
                                .l2SearchAcceptedCount(1)
                                .l2NamespaceFallbackAcceptedCount(1)
                                .finalReturnedCount(2)
                                .build())
                        .build()))
                .build();

        RecallBenchmarkReportWriter.WrittenReport writtenReport = writer.write(report);

        String markdown = Files.readString(writtenReport.markdownPath());
        String json = Files.readString(writtenReport.jsonPath());
        assertThat(markdown).contains("# Recall Benchmark Report");
        assertThat(markdown).contains("DatasetLocation: classpath:llm-memory-eval-set-v2-1-extended.json");
        assertThat(markdown).contains("EvaluationKs: 1, 3");
        assertThat(markdown).contains("Recall@K Lift vs Vector+Rerank");
        assertThat(markdown).contains("P50 (ms) | P95 (ms) | P99 (ms)");
        assertThat(markdown).contains("## Metrics By K");
        assertThat(markdown).contains("Hybrid+Rerank | 3 | 1.0000 | 0.5000 | 1.0000 | 1.0000");
        assertThat(markdown).contains(
                "keyword-lift-001 | Hybrid+Rerank | HYBRID | true | LINEAR_SCORE_FUSION | true");
        assertThat(markdown).contains(
                "keyword-lift-001 | Hybrid+Rerank | true | LINEAR_SCORE_FUSION");
        assertThat(markdown).contains("ORDER_CHANGED");
        assertThat(markdown).contains("Rerank Latency (ms)");
        assertThat(json).contains("latencyP95Ms");
        assertThat(json).contains("\"runId\" : \"abc12345\"");
        assertThat(json).contains("\"metricsByK\"");
        assertThat(json).contains("\"recallDiagnostics\"");
        assertThat(json).contains("\"rerankEffectStatus\" : \"ORDER_CHANGED\"");
        assertThat(json).contains("\"rerankerType\" : \"LINEAR_SCORE_FUSION\"");
        assertThat(json).contains("\"rerankChangedPositionCount\" : 2");
    }
}
