package com.vortex.kernel.hmc;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class EvictionRegretMetricsBinder {

    private final MeterRegistry meterRegistry;
    private final EvictionRegretTracker regretTracker;

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
    }
}
