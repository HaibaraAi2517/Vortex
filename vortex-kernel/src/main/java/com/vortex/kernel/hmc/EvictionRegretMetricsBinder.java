package com.vortex.kernel.hmc;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.stream.Collectors;

@Component
public class EvictionRegretMetricsBinder {

    private final MeterRegistry meterRegistry;
    private final EvictionRegretTracker regretTracker;
    private MultiGauge modeRateGauge;
    private MultiGauge modeCountGauge;
    private MultiGauge modeEvictionGauge;

    public EvictionRegretMetricsBinder(MeterRegistry meterRegistry, EvictionRegretTracker regretTracker) {
        this.meterRegistry = meterRegistry;
        this.regretTracker = regretTracker;
    }

    @PostConstruct
    public void bind() {
        Gauge.builder("vortex.hmc.eviction.regret.rate", regretTracker, tracker -> tracker.snapshot().regretRate())
                .description("Recent semantic eviction regret rate within the configured regret window")
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.regret.count", regretTracker, tracker -> tracker.snapshot().regretCount())
                .description("Total regret count for evicted fragments recalled from lower tiers")
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.count", regretTracker, tracker -> tracker.snapshot().evictionCount())
                .description("Total count of tracked evictions")
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.regret.pending", regretTracker, tracker -> tracker.snapshot().pendingWindowSize())
                .description("Pending evicted fragments still inside the regret observation window")
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.eviction.regret.protected.groups", regretTracker, tracker -> tracker.snapshot().protectedGroupCount())
                .description("Eviction groups temporarily protected after recent regret signals")
                .register(meterRegistry);
        modeRateGauge = MultiGauge.builder("vortex.hmc.eviction.regret.mode.rate")
                .description("Recent eviction regret rate broken down by eviction mode")
                .register(meterRegistry);
        modeCountGauge = MultiGauge.builder("vortex.hmc.eviction.regret.mode.count")
                .description("Recent eviction regret count broken down by eviction mode")
                .register(meterRegistry);
        modeEvictionGauge = MultiGauge.builder("vortex.hmc.eviction.mode.count")
                .description("Tracked eviction count broken down by eviction mode")
                .register(meterRegistry);
        refreshModeMetrics();
    }

    @Scheduled(fixedDelayString = "${vortex.kernel.metrics.refresh-interval-ms:30000}")
    void refreshModeMetrics() {
        if (modeRateGauge == null || modeCountGauge == null || modeEvictionGauge == null) {
            return;
        }
        EvictionRegretTracker.RegretSnapshot snapshot = regretTracker.snapshot();
        modeRateGauge.register(snapshot.modeBreakdown().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(
                        Tags.of("mode", entry.getKey()),
                        entry.getValue().regretRate()))
                .collect(Collectors.toList()), true);
        modeCountGauge.register(snapshot.modeBreakdown().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(
                        Tags.of("mode", entry.getKey()),
                        entry.getValue().regretCount()))
                .collect(Collectors.toList()), true);
        modeEvictionGauge.register(snapshot.modeBreakdown().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(
                        Tags.of("mode", entry.getKey()),
                        entry.getValue().evictionCount()))
                .collect(Collectors.toList()), true);
    }
}
