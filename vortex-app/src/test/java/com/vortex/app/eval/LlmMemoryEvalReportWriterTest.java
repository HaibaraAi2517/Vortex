package com.vortex.app.eval;

import com.vortex.common.dto.RecallDiagnostics;
import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMemoryEvalReportWriterTest {

    @Test
    void writeShouldIncludeEnvironmentAndGenerationTelemetry(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReportOutputDir(tempDir.toString());
        LlmMemoryEvalReportWriter writer = new LlmMemoryEvalReportWriter(JsonMapperFactory.create(), properties);

        LlmMemoryEvalReport report = LlmMemoryEvalReport.builder()
                .generatedAt(Instant.parse("2026-05-29T06:47:54Z"))
                .totalCases(1)
                .totalRuns(1)
                .results(List.of(LlmMemoryEvalResult.builder()
                        .caseId("profile-001")
                        .mode("Vortex-Memory")
                        .question("q")
                        .generatedAnswer("a")
                        .correct(true)
                        .failureReason("answer_missing_fact")
                        .runtimeErrorType("generation_timeout")
                        .transientRuntimeError(true)
                        .missingMustContain(List.of("CSV"))
                        .matchedForbiddenTerms(List.of("XLSX"))
                        .latencyMs(123L)
                        .generationLatencyMs(100L)
                        .generationLatencyNanos(100_000_000L)
                        .generationRequestBuildLatencyMs(3L)
                        .generationRequestBuildLatencyNanos(3_400_000L)
                        .generationRequestSerializationLatencyNanos(1_200_000L)
                        .generationHttpRequestBuildLatencyNanos(2_200_000L)
                        .generationHttpRoundTripLatencyMs(92L)
                        .generationHttpRoundTripLatencyNanos(92_000_000L)
                        .generationResponseParseLatencyMs(5L)
                        .generationResponseParseLatencyNanos(5_600_000L)
                        .generationResponseDecodeLatencyNanos(1_300_000L)
                        .generationResponseJsonParseLatencyNanos(4_300_000L)
                        .generationRetryBackoffLatencyMs(0L)
                        .generationRetryBackoffLatencyNanos(0L)
                        .generationAttemptCount(1)
                        .generationHttpStatusCode(200)
                        .generationRequestBytes(321)
                        .generationResponseBytes(654)
                        .recallDiagnostics(RecallDiagnostics.builder()
                                .requiredTags(List.of("llm-memory-eval-memory"))
                                .l1CandidateCount(1)
                                .l1TagMatchedCount(1)
                                .l1SelectedCount(1)
                                .finalReturnedCount(1)
                                .build())
                        .build()))
                .modeSummaries(Map.of(
                        "Vortex-Memory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(1)
                                .correct(1)
                                .accuracy(1.0d)
                                .recallHitRate(1.0d)
                                .averageLatencyMs(123.0d)
                                .build()))
                .environment(LlmMemoryEvalEnvironmentSnapshot.builder()
                        .generationBaseUrl("https://sub2.congmingai.com/v1")
                        .generationModel("gpt-5.2")
                        .generationTimeoutMs(30000L)
                        .bgeModelPath("E:/1projects/claude/Vortex/models/bge-small-zh")
                        .bgeSafeHashMode(false)
                        .l1MaxTokens(96L)
                        .milvusCollection("vortex_memory_eval_20260529_real_bge_014")
                        .minioKeyPrefix("eval/20260529-real-bge-014/")
                        .datasetLocation("classpath:llm-memory-eval-set.json")
                        .evalSystemPromptSha256("7f6d9c7d4f0cbe4f6c2c0e1b97c7b0f5b91f0d7d31ef9d0620d7f3c56f7f5a01")
                        .evalSystemPromptChars(512)
                        .modes(List.of("Vortex-Memory"))
                        .evalParallelism(32)
                        .reportOutputDir(tempDir.toString())
                        .javaVersion("21.0.10")
                        .osName("Windows 11")
                        .osVersion("10.0")
                        .userDir("E:/1projects/claude/Vortex")
                        .cliMainClass("com.vortex.app.eval.LlmMemoryEvalCliApplication")
                        .build())
                .build();

        LlmMemoryEvalReportWriter.WrittenReport writtenReport = writer.write(report);

        String markdown = Files.readString(writtenReport.markdownPath());
        String json = Files.readString(writtenReport.jsonPath());
        assertThat(markdown).contains("## Environment");
        assertThat(markdown).contains("Generation Base URL: https://sub2.congmingai.com/v1");
        assertThat(markdown).contains("Eval System Prompt SHA-256: 7f6d9c7d4f0cbe4f6c2c0e1b97c7b0f5b91f0d7d31ef9d0620d7f3c56f7f5a01");
        assertThat(markdown).contains("Eval System Prompt Chars: 512");
        assertThat(markdown).contains("Eval Parallelism: 32");
        assertThat(markdown).contains("## Recall Diagnostics");
        assertThat(markdown).contains("Failure Reason | Runtime Type | Transient Runtime");
        assertThat(markdown).contains("profile-001 | Vortex-Memory | true | answer_missing_fact | generation_timeout | true | CSV | XLSX");
        assertThat(markdown).contains("profile-001 | Vortex-Memory |  | 1 | llm-memory-eval-memory | 1 | 1 | 1");
        assertThat(markdown).contains("## Generation Telemetry");
        assertThat(markdown).contains("profile-001 | Vortex-Memory | 200 | 1 | 321 | 654");
        assertThat(json).contains("\"environment\"");
        assertThat(json).contains("\"recallDiagnostics\"");
        assertThat(json).contains("\"failureReason\" : \"answer_missing_fact\"");
        assertThat(json).contains("\"runtimeErrorType\" : \"generation_timeout\"");
        assertThat(json).contains("\"transientRuntimeError\" : true");
        assertThat(json).contains("\"missingMustContain\" : [ \"CSV\" ]");
        assertThat(json).contains("\"matchedForbiddenTerms\" : [ \"XLSX\" ]");
        assertThat(json).contains("\"generationLatencyNanos\" : 100000000");
        assertThat(json).contains("\"generationBaseUrl\" : \"https://sub2.congmingai.com/v1\"");
        assertThat(json).contains("\"evalSystemPromptSha256\" : \"7f6d9c7d4f0cbe4f6c2c0e1b97c7b0f5b91f0d7d31ef9d0620d7f3c56f7f5a01\"");
        assertThat(json).contains("\"evalParallelism\" : 32");
    }
}
