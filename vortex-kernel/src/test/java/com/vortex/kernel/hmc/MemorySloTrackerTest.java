package com.vortex.kernel.hmc;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySloTrackerTest {

    @Test
    void snapshotIncludesRollingP95AndP99Latency() {
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        tracker.bind();

        tracker.recordStoreLatency(1_000_000L);
        tracker.recordStoreLatency(5_000_000L);
        tracker.recordStoreLatency(9_000_000L);
        tracker.recordRecallLatency(2_000_000L);
        tracker.recordRecallLatency(6_000_000L);
        tracker.recordRecallLatency(10_000_000L);

        MemorySloTracker.SloSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.storeLatencyP95Ms()).isGreaterThanOrEqualTo(5.0);
        assertThat(snapshot.storeLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.storeLatencyP95Ms());
        assertThat(snapshot.recallLatencyP95Ms()).isGreaterThanOrEqualTo(6.0);
        assertThat(snapshot.recallLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.recallLatencyP95Ms());
    }

    @Test
    void rollingPercentileMaintainsWindowWithoutResortingOnRead() {
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        tracker.bind();

        for (int i = 1; i <= 600; i++) {
            tracker.recordStoreLatency(i * 1_000_000L);
        }

        MemorySloTracker.SloSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.storeCount()).isEqualTo(600);
        assertThat(snapshot.storeLatencyP95Ms()).isGreaterThanOrEqualTo(570.0);
        assertThat(snapshot.storeLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.storeLatencyP95Ms());
        assertThat(snapshot.storeLatencyP99Ms()).isLessThanOrEqualTo(600.0);
    }
}
