package com.vortex.app.eval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmMemoryEvalStartupRunnerTest {

    @Test
    void runShouldInvokeDefaultBaselinesAndWriteReports(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReportOutputDir(tempDir.toString());
        LlmMemoryEvalExecutionService executionService = mock(LlmMemoryEvalExecutionService.class);
        when(executionService.executeConfiguredRun()).thenReturn(LlmMemoryEvalReport.builder()
                .generatedAt(Instant.now())
                .totalCases(20)
                .totalRuns(40)
                .modeSummaries(Map.of(
                        "Baseline-NoMemory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(20)
                                .correct(0)
                                .accuracy(0.0d)
                                .recallHitRate(0.0d)
                                .averageLatencyMs(1.0d)
                                .build(),
                        "Vortex-Memory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(20)
                                .correct(20)
                                .accuracy(1.0d)
                                .recallHitRate(1.0d)
                                .averageLatencyMs(2.0d)
                                .build()))
                .build());

        LlmMemoryEvalStartupRunner startupRunner = new LlmMemoryEvalStartupRunner(executionService, properties);
        startupRunner.run(new DefaultApplicationArguments(new String[0]));

        verify(executionService).executeConfiguredRun();
    }

    @Test
    void runShouldSwallowFailureWhenConfigured() throws Exception {
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setFailOnStartupError(false);
        LlmMemoryEvalExecutionService executionService = mock(LlmMemoryEvalExecutionService.class);
        when(executionService.executeConfiguredRun()).thenThrow(new IllegalStateException("boom"));

        LlmMemoryEvalStartupRunner startupRunner = new LlmMemoryEvalStartupRunner(executionService, properties);
        startupRunner.run(new DefaultApplicationArguments(new String[0]));

        verify(executionService).executeConfiguredRun();
    }
}
