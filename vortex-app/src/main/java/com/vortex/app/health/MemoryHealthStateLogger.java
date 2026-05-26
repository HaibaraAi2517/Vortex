package com.vortex.app.health;

import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.hmc.MemorySloTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class MemoryHealthStateLogger {

    private final AtomicReference<ObservedState> lastObserved = new AtomicReference<>();

    public void observe(
            Status status,
            List<MemorySloHealthIndicator.HealthSummaryItem> summary,
            MemorySloTracker.SloSnapshot snapshot,
            HierarchicalMemoryController.MemoryDiagnosticsSnapshot diagnostics) {
        if (status == null || summary == null || snapshot == null) {
            return;
        }

        ObservedState current = ObservedState.of(status, summary);
        ObservedState previous = lastObserved.getAndSet(current);
        if (current.equals(previous)) {
            return;
        }

        String event = previous == null
                ? "memory_health_initialized"
                : Status.UP.equals(status) ? "memory_health_recovered" : "memory_health_degraded";

        String codes = join(summary.stream().map(MemorySloHealthIndicator.HealthSummaryItem::code).toList());
        String alertNames = join(summary.stream()
                .map(MemorySloHealthIndicator.HealthSummaryItem::alertName)
                .filter(Objects::nonNull)
                .toList());
        String warnings = diagnostics == null ? "" : join(diagnostics.warnings());

        String line = "event={} status={} previousStatus={} dictionaryVersion={} summaryCodes={} alertNames={} "
                + "checkpointRecoverySuccessRate={} persistenceSuccessRate={} durabilitySuccessRate={} "
                + "regretRate={} recallLatencyP99Ms={} storeLatencyP99Ms={} baselineLift={} "
                + "warningCount={} warnings={}";

        if (hasCritical(summary)) {
            log.error(
                    line,
                    event,
                    status.getCode(),
                    previous == null ? "unknown" : previous.statusCode(),
                    MemoryHealthSignalCatalog.DICTIONARY_VERSION,
                    codes,
                    alertNames,
                    format(snapshot.checkpointRecoverySuccessRate()),
                    format(snapshot.persistenceSuccessRate()),
                    format(snapshot.durabilitySuccessRate()),
                    format(snapshot.regretRate()),
                    format(snapshot.recallLatencyP99Ms()),
                    format(snapshot.storeLatencyP99Ms()),
                    format(snapshot.baselineRelativeLift()),
                    diagnostics == null ? 0 : diagnostics.warnings().size(),
                    warnings);
            return;
        }

        if (Status.UP.equals(status)) {
            log.info(
                    line,
                    event,
                    status.getCode(),
                    previous == null ? "unknown" : previous.statusCode(),
                    MemoryHealthSignalCatalog.DICTIONARY_VERSION,
                    codes,
                    alertNames,
                    format(snapshot.checkpointRecoverySuccessRate()),
                    format(snapshot.persistenceSuccessRate()),
                    format(snapshot.durabilitySuccessRate()),
                    format(snapshot.regretRate()),
                    format(snapshot.recallLatencyP99Ms()),
                    format(snapshot.storeLatencyP99Ms()),
                    format(snapshot.baselineRelativeLift()),
                    diagnostics == null ? 0 : diagnostics.warnings().size(),
                    warnings);
            return;
        }

        log.warn(
                line,
                event,
                status.getCode(),
                previous == null ? "unknown" : previous.statusCode(),
                MemoryHealthSignalCatalog.DICTIONARY_VERSION,
                codes,
                alertNames,
                format(snapshot.checkpointRecoverySuccessRate()),
                format(snapshot.persistenceSuccessRate()),
                format(snapshot.durabilitySuccessRate()),
                format(snapshot.regretRate()),
                format(snapshot.recallLatencyP99Ms()),
                format(snapshot.storeLatencyP99Ms()),
                format(snapshot.baselineRelativeLift()),
                diagnostics == null ? 0 : diagnostics.warnings().size(),
                warnings);
    }

    private boolean hasCritical(List<MemorySloHealthIndicator.HealthSummaryItem> summary) {
        return summary.stream().anyMatch(item -> "critical".equalsIgnoreCase(item.severity()));
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private record ObservedState(String statusCode, List<String> summaryCodes) {
        private static ObservedState of(
                Status status,
                List<MemorySloHealthIndicator.HealthSummaryItem> summary) {
            return new ObservedState(
                    status.getCode(),
                    summary.stream()
                            .map(MemorySloHealthIndicator.HealthSummaryItem::code)
                            .toList());
        }
    }
}
