package com.vortex.app.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningMemoryEvalExecutionServiceTest {

    @Test
    void executeConfiguredRunShouldFailWhenGateDoesNotPass() {
        LearningMemoryEvalRunner runner = mock(LearningMemoryEvalRunner.class);
        LearningMemoryEvalReportWriter reportWriter = mock(LearningMemoryEvalReportWriter.class);
        LearningMemoryEvalProperties properties = new LearningMemoryEvalProperties();
        LearningMemoryEvalReport report = LearningMemoryEvalReport.builder()
                .profileId(properties.getProfileId())
                .scenarioCount(5)
                .totalRecallCount(40)
                .feedbackSubmitted(40)
                .gatePassed(false)
                .aggregate(LearningMemoryEvalReport.LearningAggregate.builder()
                        .probeAllRelevantHitRate(1.0d)
                        .activeUpdateCountBefore(0L)
                        .activeUpdateCountAfter(1L)
                        .build())
                .gateChecks(java.util.List.of(LearningMemoryEvalReport.GateCheck.builder()
                        .name("rankImprovedScenarioCount")
                        .passed(false)
                        .expected(">=5")
                        .actual("3")
                        .build()))
                .build();
        when(runner.runConfiguredProfile()).thenReturn(report);
        when(reportWriter.write(report)).thenReturn(new LearningMemoryEvalReportWriter.WrittenReport(
                Path.of("learning-memory-eval.json"),
                Path.of("learning-memory-eval.md")));

        LearningMemoryEvalExecutionService executionService =
                new LearningMemoryEvalExecutionService(runner, reportWriter, properties);

        assertThatThrownBy(executionService::executeConfiguredRun)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Learning memory eval gate failed")
                .hasMessageContaining("rankImprovedScenarioCount");
        verify(reportWriter).write(report);
    }
}
