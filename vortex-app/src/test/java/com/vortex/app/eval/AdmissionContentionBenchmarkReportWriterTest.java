package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionContentionBenchmarkReportWriterTest {

    @Test
    void writesJsonAndMarkdownReports(@TempDir Path tempDir) throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReportOutputDir(tempDir.toString());
        AdmissionContentionBenchmarkReportWriter writer =
                new AdmissionContentionBenchmarkReportWriter(
                        new ObjectMapper().findAndRegisterModules(),
                        properties);
        AdmissionContentionBenchmarkReport report = AdmissionContentionBenchmarkReport.builder()
                .generatedAt(Instant.parse("2026-08-02T12:00:00Z"))
                .runId("run-1")
                .benchmarkScope("scope")
                .successDefinition("all admitted")
                .operationsPerThread(10)
                .warmupOperationsPerThread(1)
                .tokenCountPerFragment(1)
                .parallelismLevels(List.of(1, 4))
                .results(List.of(AdmissionContentionBenchmarkReport.ParallelismResult.builder()
                        .parallelism(4)
                        .attempted(40)
                        .admitted(40)
                        .successRate(1.0)
                        .throughputPerSecond(2_000.0)
                        .latencyP95Ms(2.5)
                        .latencyP99Ms(3.5)
                        .lockAcquisitions(40)
                        .lockAcquisitionsPerRequest(1.0)
                        .lockWaitAverageMs(0.2)
                        .lockHoldAverageMs(0.3)
                        .planningAverageMs(0.4)
                        .directAttempts(40)
                        .directCommits(40)
                        .optimisticAttempts(45)
                        .optimisticConflicts(5)
                        .optimisticConflictRate(5.0 / 45.0)
                        .fallbacks(1)
                        .fallbackRate(0.025)
                        .errorMessages(List.of())
                        .build()))
                .build();

        AdmissionContentionBenchmarkReportWriter.WrittenReport written = writer.write(report);

        assertThat(written.jsonPath()).exists();
        assertThat(written.markdownPath()).exists();
        assertThat(Files.readString(written.markdownPath()))
                .contains("# Admission Contention Benchmark Report")
                .contains("| 4 | 40 | 40 |")
                .contains("Locks/Request")
                .contains("Direct Attempts")
                .contains("Optimistic Attempts")
                .contains("Conflict Rate");
    }
}
