package com.vortex.app.health;

import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.health.MemoryDiagnosticSignal;
import com.vortex.common.health.MemoryHealthCodes;
import com.vortex.kernel.hmc.MemoryDiagnosticsCollector;
import com.vortex.kernel.hmc.MemorySloTracker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySloHealthIndicatorTest {

    private final MemoryHealthStateLogger stateLogger = new MemoryHealthStateLogger();

    @Test
    void reportsDownWhenSloThresholdsAreBreached() {
        MemorySloHealthIndicator indicator = new MemorySloHealthIndicator(
                () -> new MemorySloTracker.SloSnapshot(
                        10,
                        10,
                        0.9,
                        0.08,
                        -0.1,
                        0.2,
                        0.4,
                        1,
                        0.5,
                        0.5,
                        0.5,
                        0.5,
                        12.0,
                        11.0,
                        11.5,
                        13.0,
                        12.0,
                        12.5,
                        0,
                        0,
                        0),
                () -> diagnostics(List.of("prefetch strategy semantic-nbhd degraded")),
                stateLogger,
                1.0,
                0.05,
                0,
                1.0,
                1.0,
                10.0,
                10.0,
                -0.05,
                0.20,
                0.90,
                1);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsUpWhenSloThresholdsRemainHealthy() {
        MemorySloHealthIndicator indicator = new MemorySloHealthIndicator(
                () -> new MemorySloTracker.SloSnapshot(
                        10,
                        10,
                        1.0,
                        0.01,
                        0.1,
                        0.25,
                        0.95,
                        0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        5.0,
                        4.0,
                        5.0,
                        6.0,
                        5.5,
                        6.0,
                        0,
                        0,
                        0),
                () -> diagnostics(List.of()),
                stateLogger,
                1.0,
                0.05,
                0,
                1.0,
                1.0,
                10.0,
                10.0,
                -0.05,
                0.20,
                0.90,
                1);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDegradedWhenOnlyWarningThresholdsAreBreached() {
        MemorySloHealthIndicator indicator = new MemorySloHealthIndicator(
                () -> new MemorySloTracker.SloSnapshot(
                        10,
                        10,
                        0.9,
                        0.01,
                        0.0,
                        0.25,
                        1.0,
                        0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        12.0,
                        4.0,
                        5.0,
                        6.0,
                        5.5,
                        6.0,
                        0,
                        0,
                        0),
                () -> diagnostics(List.of()),
                stateLogger,
                1.0,
                0.05,
                0,
                1.0,
                1.0,
                10.0,
                10.0,
                -0.05,
                0.20,
                0.90,
                1);

        assertThat(indicator.health().getStatus()).isEqualTo(MemorySloHealthIndicator.DEGRADED);
    }

    @Test
    void healthDetailsIncludeDiagnosticSummaryWhenAvailable() {
        MemorySloHealthIndicator indicator = new MemorySloHealthIndicator(
                () -> new MemorySloTracker.SloSnapshot(
                        1, 1, 1.0, 0.10, 0.1, 0.3, 1.0, 0, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0, 0, 0),
                () -> diagnostics(List.of("eviction mode semantic regret elevated")),
                stateLogger,
                1.0,
                0.05,
                0,
                1.0,
                1.0,
                10.0,
                10.0,
                -0.05,
                0.20,
                0.90,
                1);

        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getDetails()).containsKeys("diagnosticWarnings", "diagnosticSignals", "prefetchStrategies", "regretModes", "learningScenarios", "summary", "dictionaryVersion");
        assertThat(health.getDetails().get("diagnosticWarnings").toString()).contains("semantic");
        assertThat(health.getDetails().get("diagnosticSignals").toString()).contains(MemoryHealthCodes.EVICTION_REGRET_MODE_HIGH);
        assertThat(health.getDetails().get("summary").toString()).contains("eviction_regret_high");
        assertThat(health.getDetails().get("summary").toString()).contains("VortexMemoryEvictionRegretHigh");
        assertThat(health.getDetails().get("summary").toString()).contains("ops/runbooks/memory-health-signals.md#eviction_regret_high");
    }

    @Test
    void summaryPrioritizesHighestSeveritySignalsAndLimitsOutput() {
        MemorySloHealthIndicator indicator = new MemorySloHealthIndicator(
                () -> new MemorySloTracker.SloSnapshot(
                        10,
                        10,
                        0.5,
                        0.30,
                        -0.2,
                        0.05,
                        0.20,
                        2,
                        0.5,
                        0.5,
                        0.5,
                        0.5,
                        20.0,
                        15.0,
                        18.0,
                        25.0,
                        20.0,
                        21.0,
                        0,
                        0,
                        0),
                () -> diagnostics(List.of(
                        "prefetch strategy semantic-nbhd degraded",
                        "paging drift elevated",
                        "learning regression in chat")),
                stateLogger,
                1.0,
                0.05,
                0,
                1.0,
                1.0,
                10.0,
                10.0,
                -0.05,
                0.20,
                0.90,
                1);

        org.springframework.boot.actuate.health.Health health = indicator.health();
        String summary = health.getDetails().get("summary").toString();

        assertThat(summary).contains("namespace_isolation_violation");
        assertThat(summary).contains("checkpoint_recovery_success_rate_low");
        assertThat(summary).contains("memory_persistence_success_rate_low");
        assertThat(summary).doesNotContain("diagnostic_warning");
    }

    @Test
    void ignoresLearningThresholdsUntilLearningSamplesExist() {
        MemorySloHealthIndicator indicator = new MemorySloHealthIndicator(
                () -> new MemorySloTracker.SloSnapshot(
                        10,
                        10,
                        1.0,
                        0.01,
                        -0.5,
                        0.0,
                        0.0,
                        0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        5.0,
                        4.0,
                        5.0,
                        6.0,
                        5.5,
                        6.0,
                        0,
                        0,
                        0),
                MemorySloHealthIndicatorTest::diagnosticsWithoutLearningSamples,
                stateLogger,
                1.0,
                0.05,
                0,
                1.0,
                1.0,
                10.0,
                10.0,
                -0.05,
                0.20,
                0.90,
                1);

        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("learningEvaluationActive", false);
        assertThat(health.getDetails()).containsEntry("learningSampleCount", 0L);
        assertThat(health.getDetails().get("summary").toString()).doesNotContain("baseline_lift_low");
    }

    private static MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot diagnostics(List<String> warnings) {
        List<MemoryDiagnosticSignal> signals = warnings.stream()
                .map(MemorySloHealthIndicatorTest::signalForWarning)
                .toList();
        return new MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot(
                new MemorySloTracker.SloSnapshot(
                        1, 1, 1.0, 0.0, 0.1, 0.3, 1.0, 0, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0, 0, 0),
                new MemoryDiagnosticsCollector.PagingDiagnosticsSnapshot(
                        4,
                        2,
                        2,
                        new com.vortex.kernel.paging.SemanticPageTable.AssignmentStats(10, 4, 6, 0.4, 0.6),
                        List.of(new MemoryDiagnosticsCollector.PrefetchStrategyDiagnostic("semantic-nbhd", 25, 2, 23, 0.08, 1))),
                new MemoryDiagnosticsCollector.RegretDiagnosticsSnapshot(
                        20,
                        3,
                        0.15,
                        2,
                        1,
                        List.of(new MemoryDiagnosticsCollector.RegretModeDiagnostic("semantic", 20, 3, 0.15))),
                List.of(new MemoryDiagnosticsCollector.LearningScenarioDiagnostic(
                        MemoryScenario.CHAT,
                        "chat-active",
                        "chat-shadow",
                        -0.10,
                        0.05,
                        12,
                        0)),
                signals,
                warnings);
    }

    private static MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot diagnosticsWithoutLearningSamples() {
        return new MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot(
                new MemorySloTracker.SloSnapshot(
                        1, 1, 1.0, 0.0, 0.0, 0.0, 0.0, 0, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0, 0, 0),
                new MemoryDiagnosticsCollector.PagingDiagnosticsSnapshot(
                        0,
                        0,
                        0,
                        new com.vortex.kernel.paging.SemanticPageTable.AssignmentStats(0, 0, 0, 0.0, 0.0),
                        List.of()),
                new MemoryDiagnosticsCollector.RegretDiagnosticsSnapshot(
                        0,
                        0,
                        0.0,
                        0,
                        0,
                        List.of()),
                List.of(new MemoryDiagnosticsCollector.LearningScenarioDiagnostic(
                        MemoryScenario.CHAT,
                        "chat-active",
                        "chat-shadow",
                        0.0,
                        0.0,
                        0,
                        0)),
                List.of(),
                List.of());
    }

    private static MemoryDiagnosticSignal signalForWarning(String warning) {
        if (warning.startsWith("prefetch strategy")) {
            return new MemoryDiagnosticSignal(
                    MemoryHealthCodes.PREFETCH_STRATEGY_DEGRADED,
                    "warning",
                    "prefetch",
                    warning,
                    java.util.Map.of("strategy", "semantic-nbhd"));
        }
        if (warning.startsWith("eviction mode")) {
            return new MemoryDiagnosticSignal(
                    MemoryHealthCodes.EVICTION_REGRET_MODE_HIGH,
                    "warning",
                    "eviction",
                    warning,
                    java.util.Map.of("mode", "semantic"));
        }
        if (warning.startsWith("paging drift")) {
            return new MemoryDiagnosticSignal(
                    MemoryHealthCodes.PAGING_DRIFT_HIGH,
                    "warning",
                    "paging",
                    warning,
                    java.util.Map.of("assignments", 10));
        }
        if (warning.startsWith("learning regression")) {
            return new MemoryDiagnosticSignal(
                    MemoryHealthCodes.LEARNING_REGRESSION,
                    "warning",
                    "learning",
                    warning,
                    java.util.Map.of("scenario", "chat"));
        }
        return new MemoryDiagnosticSignal(
                MemoryHealthCodes.DIAGNOSTIC_WARNING,
                "warning",
                "general",
                warning,
                java.util.Map.of());
    }
}
