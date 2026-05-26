package com.vortex.kernel.hmc;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvictionRegretMetricsBinderTest {

    @Test
    void bindRegistersRegretMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EvictionRegretTracker tracker = new EvictionRegretTracker(60_000L, System::currentTimeMillis);
        EvictionRegretMetricsBinder binder = new EvictionRegretMetricsBinder(registry, tracker);

        binder.bind();

        assertThat(registry.find("vortex.hmc.eviction.regret.rate").gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.eviction.regret.count").gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.eviction.count").gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.eviction.regret.pending").gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.eviction.regret.protected.groups").gauge()).isNotNull();
    }

    @Test
    void refreshModeMetricsRegistersTaggedModeBreakdown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EvictionRegretTracker tracker = new EvictionRegretTracker(60_000L, System::currentTimeMillis);
        EvictionRegretMetricsBinder binder = new EvictionRegretMetricsBinder(registry, tracker);
        com.vortex.common.model.MemoryFragment fragment = com.vortex.common.model.MemoryFragment.builder()
                .id("f-1")
                .namespace("ns")
                .content("content")
                .tokenCount(1)
                .build();

        binder.bind();
        tracker.recordEviction(fragment, "semantic");
        tracker.recordRecall(fragment, "L2");
        binder.refreshModeMetrics();

        assertThat(registry.find("vortex.hmc.eviction.regret.mode.rate")
                .tags("mode", "semantic")
                .gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.eviction.regret.mode.count")
                .tags("mode", "semantic")
                .gauge()).isNotNull();
    }
}
