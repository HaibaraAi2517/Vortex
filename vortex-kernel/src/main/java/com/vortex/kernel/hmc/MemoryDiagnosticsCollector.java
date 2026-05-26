package com.vortex.kernel.hmc;

import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.health.MemoryDiagnosticSignal;
import com.vortex.common.health.MemoryHealthCodes;
import com.vortex.kernel.paging.PrefetchEngine;
import com.vortex.kernel.paging.SemanticPagingManager;
import com.vortex.kernel.paging.SemanticPageTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Collects health diagnostics snapshots from memory subsystems.
 *
 * Extracted from {@link HierarchicalMemoryController} to keep diagnostics
 * aggregation self-contained. All subsystem references are injected and
 * the paging subsystem is optional (via {@link ObjectProvider}).
 */
@Slf4j
@Component
public class MemoryDiagnosticsCollector {

    private final MemorySloTracker sloTracker;
    private final EvictionRegretTracker regretTracker;
    private final SemanticPagingManager pagingManager;
    private final AdaptiveWeightLearner adaptiveWeightLearner;
    private final AtomicReference<Set<String>> activeDiagnosticSignalKeys = new AtomicReference<>(Set.of());

    @Autowired
    public MemoryDiagnosticsCollector(
            MemorySloTracker sloTracker,
            EvictionRegretTracker regretTracker,
            ObjectProvider<SemanticPagingManager> pagingManagerProvider,
            AdaptiveWeightLearner adaptiveWeightLearner) {
        this.sloTracker = sloTracker;
        this.regretTracker = regretTracker;
        this.pagingManager = pagingManagerProvider.getIfAvailable();
        this.adaptiveWeightLearner = adaptiveWeightLearner;
    }

    /**
     * Builds a complete diagnostics snapshot across all memory subsystems:
     * SLO tracking, paging, eviction regret, and adaptive weight learning.
     */
    public MemoryDiagnosticsSnapshot diagnosticsSnapshot() {
        MemorySloTracker.SloSnapshot slo = sloTracker.snapshot();
        EvictionRegretTracker.RegretSnapshot regret = regretTracker.snapshot();
        PagingDiagnosticsSnapshot paging = pagingManager == null
                ? PagingDiagnosticsSnapshot.disabled()
                : buildPagingDiagnosticsSnapshot();
        List<LearningScenarioDiagnostic> learning = Arrays.stream(MemoryScenario.values())
                .map(this::buildLearningScenarioDiagnostic)
                .toList();
        List<MemoryDiagnosticSignal> signals = new ArrayList<>();

        paging.prefetchStrategies().stream()
                .filter(strategy -> strategy.requested() >= 10 && strategy.hitRate() < 0.10)
                .sorted(Comparator.comparingDouble(PrefetchStrategyDiagnostic::hitRate))
                .forEach(strategy -> signals.add(diagnosticSignal(
                        MemoryHealthCodes.PREFETCH_STRATEGY_DEGRADED,
                        "warning",
                        "prefetch",
                        "prefetch strategy " + strategy.source() + " degraded: hitRate="
                                + formatDouble(strategy.hitRate()) + ", requested=" + strategy.requested(),
                        Map.of(
                                "strategy", strategy.source(),
                                "hitRate", formatDouble(strategy.hitRate()),
                                "requested", strategy.requested(),
                                "effectiveBudget", strategy.effectiveBudget()))));
        regret.modeBreakdown().entrySet().stream()
                .filter(entry -> entry.getValue().evictionCount() >= 5 && entry.getValue().regretRate() > 0.10)
                .sorted((left, right) -> Double.compare(right.getValue().regretRate(), left.getValue().regretRate()))
                .forEach(entry -> signals.add(diagnosticSignal(
                        MemoryHealthCodes.EVICTION_REGRET_MODE_HIGH,
                        "warning",
                        "eviction",
                        "eviction mode " + entry.getKey() + " regret elevated: rate="
                                + formatDouble(entry.getValue().regretRate()) + ", regrets=" + entry.getValue().regretCount(),
                        Map.of(
                                "mode", entry.getKey(),
                                "regretRate", formatDouble(entry.getValue().regretRate()),
                                "regretCount", entry.getValue().regretCount(),
                                "evictionCount", entry.getValue().evictionCount()))));
        if (paging.assignment().incrementalAssignments() >= 10 && paging.assignment().incrementalNewPageRatio() > 0.50) {
            signals.add(diagnosticSignal(
                    MemoryHealthCodes.PAGING_DRIFT_HIGH,
                    "warning",
                    "paging",
                    "paging drift elevated: newPageRate="
                            + formatDouble(paging.assignment().incrementalNewPageRatio())
                            + ", assignments=" + paging.assignment().incrementalAssignments(),
                    Map.of(
                            "newPageRate", formatDouble(paging.assignment().incrementalNewPageRatio()),
                            "reuseRate", formatDouble(paging.assignment().incrementalReuseRatio()),
                            "assignments", paging.assignment().incrementalAssignments())));
        }
        learning.stream()
                .filter(item -> item.sampleCount() >= 10 && item.shadowRelativeLift() < -0.05)
                .sorted(Comparator.comparingDouble(LearningScenarioDiagnostic::shadowRelativeLift))
                .forEach(item -> signals.add(diagnosticSignal(
                        MemoryHealthCodes.LEARNING_REGRESSION,
                        "warning",
                        "learning",
                        "learning regression in " + item.scenario().name().toLowerCase(Locale.ROOT) + ": shadowLift="
                                + formatDouble(item.shadowRelativeLift()) + ", baselineLift="
                                + formatDouble(item.baselineRelativeLift()),
                        Map.of(
                                "scenario", item.scenario().name().toLowerCase(Locale.ROOT),
                                "shadowLift", formatDouble(item.shadowRelativeLift()),
                                "baselineLift", formatDouble(item.baselineRelativeLift()),
                                "sampleCount", item.sampleCount()))));

        logDiagnosticSignalState(signals);

        return new MemoryDiagnosticsSnapshot(
                slo,
                paging,
                new RegretDiagnosticsSnapshot(
                        regret.evictionCount(),
                        regret.regretCount(),
                        regret.regretRate(),
                        regret.pendingWindowSize(),
                        regret.protectedGroupCount(),
                        regret.modeBreakdown().entrySet().stream()
                                .map(entry -> new RegretModeDiagnostic(
                                        entry.getKey(),
                                        entry.getValue().evictionCount(),
                                        entry.getValue().regretCount(),
                                        entry.getValue().regretRate()))
                                .sorted((left, right) -> Double.compare(right.regretRate(), left.regretRate()))
                                .toList()),
                learning,
                List.copyOf(signals),
                signals.stream().map(MemoryDiagnosticSignal::message).toList());
    }

    private PagingDiagnosticsSnapshot buildPagingDiagnosticsSnapshot() {
        SemanticPagingManager.PagingStats stats = pagingManager.getStats();
        List<PrefetchStrategyDiagnostic> strategies = PrefetchEngine.METRIC_STRATEGY_SOURCES.stream()
                .filter(source -> !"manual".equals(source))
                .map(source -> {
                    PrefetchEngine.StrategySnapshot snapshot = pagingManager.getPrefetchEngine().strategySnapshot(source);
                    return new PrefetchStrategyDiagnostic(
                            source,
                            snapshot.requested(),
                            snapshot.consumed(),
                            snapshot.missed(),
                            snapshot.hitRate(),
                            snapshot.effectiveBudget());
                })
                .toList();
        return new PagingDiagnosticsSnapshot(
                stats.totalPages(),
                stats.residentPages(),
                stats.evictedPages(),
                pagingManager.getPageTable().assignmentStats(),
                strategies);
    }

    private LearningScenarioDiagnostic buildLearningScenarioDiagnostic(MemoryScenario scenario) {
        AdaptiveWeightLearner.LearningSnapshot snapshot = adaptiveWeightLearner.snapshot(scenario);
        return new LearningScenarioDiagnostic(
                scenario,
                snapshot.active() == null ? null : snapshot.active().getProfileName(),
                snapshot.shadow() == null ? null : snapshot.shadow().getProfileName(),
                snapshot.shadowEvaluation().relativeLift(),
                snapshot.shadowEvaluation().baselineRelativeLift(),
                snapshot.shadowEvaluation().sampleCount(),
                snapshot.pendingRecallSessions());
    }

    private String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private MemoryDiagnosticSignal diagnosticSignal(
            String code,
            String severity,
            String source,
            String message,
            Map<String, Object> attributes) {
        return new MemoryDiagnosticSignal(code, severity, source, message, attributes);
    }

    private void logDiagnosticSignalState(List<MemoryDiagnosticSignal> signals) {
        Set<String> currentKeys = signals.stream()
                .map(this::signalKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> previousKeys = activeDiagnosticSignalKeys.getAndSet(Set.copyOf(currentKeys));

        for (MemoryDiagnosticSignal signal : signals) {
            String key = signalKey(signal);
            if (!previousKeys.contains(key)) {
                log.warn(
                        "memory_diagnostic_activated healthCode={} source={} severity={} message={} attributes={}",
                        signal.code(),
                        signal.source(),
                        signal.severity(),
                        signal.message(),
                        signal.attributes());
            }
        }

        for (String previousKey : previousKeys) {
            if (currentKeys.contains(previousKey)) {
                continue;
            }
            String[] parts = previousKey.split("\\|", 3);
            log.info(
                    "memory_diagnostic_recovered healthCode={} source={} message={}",
                    parts.length > 0 ? parts[0] : MemoryHealthCodes.DIAGNOSTIC_WARNING,
                    parts.length > 1 ? parts[1] : "unknown",
                    parts.length > 2 ? parts[2] : previousKey);
        }
    }

    private String signalKey(MemoryDiagnosticSignal signal) {
        return signal.code() + "|" + signal.source() + "|" + signal.message();
    }

    /**
     * Top-level diagnostics snapshot aggregating all memory subsystem health.
     */
    public record MemoryDiagnosticsSnapshot(
            MemorySloTracker.SloSnapshot slo,
            PagingDiagnosticsSnapshot paging,
            RegretDiagnosticsSnapshot regret,
            List<LearningScenarioDiagnostic> learning,
            List<MemoryDiagnosticSignal> signals,
            List<String> warnings) {
    }

    /**
     * Paging subsystem diagnostics snapshot.
     */
    public record PagingDiagnosticsSnapshot(
            int totalPages,
            int residentPages,
            int evictedPages,
            SemanticPageTable.AssignmentStats assignment,
            List<PrefetchStrategyDiagnostic> prefetchStrategies) {
        private static PagingDiagnosticsSnapshot disabled() {
            return new PagingDiagnosticsSnapshot(
                    0,
                    0,
                    0,
                    new SemanticPageTable.AssignmentStats(0, 0, 0, 0.0, 0.0),
                    List.of());
        }
    }

    /**
     * Per-strategy prefetch effectiveness diagnostic.
     */
    public record PrefetchStrategyDiagnostic(
            String source,
            long requested,
            long consumed,
            long missed,
            double hitRate,
            int effectiveBudget) {
    }

    /**
     * Eviction regret diagnostics snapshot.
     */
    public record RegretDiagnosticsSnapshot(
            long evictionCount,
            long regretCount,
            double regretRate,
            int pendingWindowSize,
            int protectedGroupCount,
            List<RegretModeDiagnostic> modes) {
    }

    /**
     * Per-eviction-mode regret breakdown.
     */
    public record RegretModeDiagnostic(
            String mode,
            long evictionCount,
            long regretCount,
            double regretRate) {
    }

    /**
     * Per-scenario adaptive weight learning diagnostic.
     */
    public record LearningScenarioDiagnostic(
            MemoryScenario scenario,
            String activeProfileName,
            String shadowProfileName,
            double shadowRelativeLift,
            double baselineRelativeLift,
            long sampleCount,
            int pendingRecallSessions) {
    }
}
