package com.vortex.app.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmMemoryEvalExecutionServiceTest {

    @Test
    void executeConfiguredRunShouldDelegateAndWriteReports(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReportOutputDir(tempDir.toString());
        LlmMemoryEvalRunner evalRunner = mock(LlmMemoryEvalRunner.class);
        LlmMemoryEvalEnvironmentSnapshotFactory environmentSnapshotFactory = mock(LlmMemoryEvalEnvironmentSnapshotFactory.class);
        when(evalRunner.runConfiguredModes()).thenReturn(LlmMemoryEvalReport.builder()
                .generatedAt(Instant.now())
                .totalCases(20)
                .totalRuns(60)
                .results(List.of(LlmMemoryEvalResult.builder()
                        .actualGenerationModel("gpt-5.4")
                        .build()))
                .modeSummaries(Map.of(
                        "Baseline-NoMemory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(20)
                                .correct(0)
                                .accuracy(0.0d)
                                .recallHitRate(0.0d)
                                .averageLatencyMs(1.0d)
                                .build()))
                .build());
        when(environmentSnapshotFactory.snapshot()).thenReturn(LlmMemoryEvalEnvironmentSnapshot.builder()
                .generationBaseUrl("https://sub2.congmingai.com/v1")
                .generationModel("gpt-5.2")
                .build());

        LlmMemoryEvalReportWriter reportWriter = new LlmMemoryEvalReportWriter(
                com.vortex.common.serialization.JsonMapperFactory.create(), properties);
        LlmMemoryEvalExecutionService executionService =
                new LlmMemoryEvalExecutionService(evalRunner, reportWriter, properties, environmentSnapshotFactory);

        LlmMemoryEvalReport report = executionService.executeConfiguredRun();

        assertThat(report.getTotalRuns()).isEqualTo(60);
        assertThat(report.getEnvironment()).isNotNull();
        assertThat(report.getEnvironment().getGenerationBaseUrl()).isEqualTo("https://sub2.congmingai.com/v1");
        assertThat(report.getEnvironment().getActualGenerationModels()).containsExactly("gpt-5.4");
        verify(evalRunner).runConfiguredModes();
        verify(environmentSnapshotFactory).snapshot();
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(Path::getFileName).map(Path::toString).toList())
                    .hasSize(2)
                    .anyMatch(name -> name.endsWith(".json"))
                    .anyMatch(name -> name.endsWith(".md"));
        }
    }
}
