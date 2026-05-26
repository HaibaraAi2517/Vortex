package com.vortex.app.health;

import com.vortex.common.health.MemoryDiagnosticSignal;
import com.vortex.common.health.MemoryHealthCodes;
import com.vortex.kernel.hmc.MemoryDiagnosticsCollector;
import com.vortex.kernel.hmc.MemorySloTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class MemoryHealthStateLoggerTest {

    @Test
    void logsOnlyWhenObservedHealthStateChanges(CapturedOutput output) {
        MemoryHealthStateLogger stateLogger = new MemoryHealthStateLogger();
        MemorySloHealthIndicator indicator = new MemorySloHealthIndicator(
                () -> new MemorySloTracker.SloSnapshot(
                        10, 10, 1.0, 0.01, 0.1, 0.25, 0.95, 0, 1.0, 1.0, 1.0, 1.0,
                        5.0, 4.0, 5.0, 6.0, 5.5, 6.0, 0, 0, 0),
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

        indicator.health();
        indicator.health();

        assertThat(countMatches(output.toString(), "event=memory_health_initialized")).isEqualTo(1);
        assertThat(output.toString()).contains("summaryCodes=healthy");

        MemorySloHealthIndicator degraded = new MemorySloHealthIndicator(
                () -> new MemorySloTracker.SloSnapshot(
                        10, 10, 1.0, 0.20, 0.1, 0.10, 0.40, 0, 1.0, 1.0, 1.0, 1.0,
                        5.0, 4.0, 5.0, 6.0, 5.5, 6.0, 0, 0, 0),
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

        degraded.health();

        assertThat(output.toString()).contains("event=memory_health_degraded");
        assertThat(output.toString()).contains("summaryCodes=eviction_regret_high,eviction_regret_mode_high");
        assertThat(output.toString()).contains("warnings=eviction mode semantic regret elevated");
    }

    private static MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot diagnostics(List<String> warnings) {
        List<MemoryDiagnosticSignal> signals = warnings.stream()
                .map(warning -> new MemoryDiagnosticSignal(
                        MemoryHealthCodes.EVICTION_REGRET_MODE_HIGH,
                        "warning",
                        "eviction",
                        warning,
                        java.util.Map.of("mode", "semantic")))
                .toList();
        return new MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot(
                new MemorySloTracker.SloSnapshot(
                        1, 1, 1.0, 0.0, 0.1, 0.3, 1.0, 0, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0, 0, 0),
                new MemoryDiagnosticsCollector.PagingDiagnosticsSnapshot(
                        1,
                        1,
                        0,
                        new com.vortex.kernel.paging.SemanticPageTable.AssignmentStats(1, 1, 0, 1.0, 0.0),
                        List.of()),
                new MemoryDiagnosticsCollector.RegretDiagnosticsSnapshot(
                        10,
                        2,
                        0.2,
                        1,
                        1,
                        List.of(new MemoryDiagnosticsCollector.RegretModeDiagnostic("semantic", 10, 2, 0.2))),
                List.of(),
                signals,
                warnings);
    }

    private static int countMatches(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
