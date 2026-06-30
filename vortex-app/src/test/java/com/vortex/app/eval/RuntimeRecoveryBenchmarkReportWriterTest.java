package com.vortex.app.eval;

import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeRecoveryBenchmarkReportWriterTest {

    @Test
    void writeShouldIncludeSummaryCapabilitiesAndResults(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReportOutputDir(tempDir.toString());
        RuntimeRecoveryBenchmarkReportWriter writer =
                new RuntimeRecoveryBenchmarkReportWriter(JsonMapperFactory.create(), properties);

        RuntimeRecoveryBenchmarkReport report = RuntimeRecoveryBenchmarkReport.builder()
                .generatedAt(Instant.parse("2026-06-26T01:02:03Z"))
                .runId("run12345")
                .totalCases(1)
                .passedCases(1)
                .failedCases(0)
                .successRate(1.0d)
                .totalLatencyMs(12L)
                .averageLatencyMs(12.0d)
                .successDefinition("Recovered state matches expected task/runtime state")
                .randomSeed(20260629L)
                .coveredCapabilities(List.of("Task DAG checkpoint and recover"))
                .excludedCapabilities(List.of("LLM timeout task-level resume"))
                .categorySummaries(List.of(RuntimeRecoveryBenchmarkReport.CategorySummary.builder()
                        .category("Service restart")
                        .totalCases(1)
                        .passedCases(1)
                        .failedCases(0)
                        .successRate(1.0d)
                        .totalLatencyMs(12L)
                        .averageLatencyMs(12.0d)
                        .build()))
                .results(List.of(RuntimeRecoveryBenchmarkReport.CaseResult.builder()
                        .caseId("runtime-recovery-001")
                        .name("Checkpoint recovery")
                        .category("Service restart")
                        .capability("checkpoint-recover")
                        .passed(true)
                        .taskId("task-1")
                        .checkpointId("checkpoint-1")
                        .latencyMs(12L)
                        .expected("status=RUNNING")
                        .actual("status=RUNNING")
                        .details(Map.of("nodeCount", "1"))
                        .build()))
                .build();

        RuntimeRecoveryBenchmarkReportWriter.WrittenReport written = writer.write(report);

        String markdown = Files.readString(written.markdownPath());
        String json = Files.readString(written.jsonPath());
        assertThat(markdown).contains("# Runtime Recovery Benchmark Report");
        assertThat(markdown).contains("SuccessRate: 1.0000");
        assertThat(markdown).contains("RandomSeed: 20260629");
        assertThat(markdown).contains("Recovered state matches expected task/runtime state");
        assertThat(markdown).contains("## Category Success Rates");
        assertThat(markdown).contains("Service restart | 1 | 1 | 0 | 1.0000");
        assertThat(markdown).contains("Task DAG checkpoint and recover");
        assertThat(markdown).contains("LLM timeout task-level resume");
        assertThat(markdown).contains("runtime-recovery-001 | Service restart | checkpoint-recover | true");
        assertThat(json).contains("\"runId\" : \"run12345\"");
        assertThat(json).contains("\"successRate\" : 1.0");
        assertThat(json).contains("\"category\" : \"Service restart\"");
    }
}