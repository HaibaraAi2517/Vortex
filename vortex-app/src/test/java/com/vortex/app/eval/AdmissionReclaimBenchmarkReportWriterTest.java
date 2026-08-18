package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionReclaimBenchmarkReportWriterTest {

    @Test
    void writesJsonAndMarkdownReports(@TempDir Path tempDir) throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReportOutputDir(tempDir.toString());
        AdmissionReclaimBenchmarkReportWriter writer =
                new AdmissionReclaimBenchmarkReportWriter(
                        new ObjectMapper().findAndRegisterModules(),
                        properties);
        AdmissionReclaimBenchmarkReport report = AdmissionReclaimBenchmarkReport.builder()
                .generatedAt(Instant.parse("2026-08-04T12:00:00Z"))
                .runId("run-1")
                .benchmarkScope("scope")
                .successDefinition("all admitted")
                .residentFragmentTarget(512)
                .embeddingDimensions(512)
                .warmupOperationsPerThread(1)
                .persistenceDrained(true)
                .results(List.of(AdmissionReclaimBenchmarkReport.ScenarioResult.builder()
                        .scenario("reasoning-chain-20")
                        .victimGroupSize(20)
                        .parallelism(1)
                        .operationsPerThread(1)
                        .residentFragmentsBefore(512)
                        .attempted(1)
                        .admitted(1)
                        .actualEvictedFragments(20)
                        .expectedEvictedFragments(20)
                        .throughputPerSecond(100.0)
                        .latencyP95Ms(10.0)
                        .latencyP99Ms(10.0)
                        .planningGateWaitCount(1)
                        .planningGateWaitTotalMs(0.4)
                        .planningGateWaitAverageMs(0.4)
                        .lockAcquisitionsPerRequest(3.0)
                        .detailedSnapshotLockHoldAverageMs(0.2)
                        .detailedSnapshotFreezeAverageMs(1.5)
                        .planningAverageMs(2.0)
                        .commitLockHoldAverageMs(0.8)
                        .errorMessages(List.of())
                        .build()))
                .build();

        AdmissionReclaimBenchmarkReportWriter.WrittenReport written = writer.write(report);

        assertThat(written.jsonPath()).exists();
        assertThat(written.markdownPath()).exists();
        assertThat(Files.readString(written.markdownPath()))
                .contains("# Admission Reclaim Benchmark Report")
                .contains("- WarmupOperationsPerThread: 1")
                .contains("- PersistenceDrained: true")
                .contains("| reasoning-chain-20 | 20 | 1 |")
                .contains("Gate Wait Total")
                .contains("Gate Wait Avg")
                .contains("Detailed Snapshot Lock Avg")
                .contains("Commit Lock Avg");
    }
}
