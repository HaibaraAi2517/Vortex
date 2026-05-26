package com.vortex.kernel.hmc;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Test
    void concurrentBurstyLatencyRecordingKeepsTailMetricsStable() throws Exception {
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        tracker.bind();

        int threads = 8;
        int iterationsPerThread = 400;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                for (int i = 1; i <= iterationsPerThread; i++) {
                    long storeNanos = (i % 100 == 0) ? 80_000_000L : 1_000_000L;
                    long recallNanos = (i % 120 == 0) ? 120_000_000L : 2_000_000L;
                    tracker.recordStoreLatency(storeNanos);
                    tracker.recordRecallLatency(recallNanos);
                }
            });
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        MemorySloTracker.SloSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.storeCount()).isEqualTo((long) threads * iterationsPerThread);
        assertThat(snapshot.recallCount()).isEqualTo((long) threads * iterationsPerThread);
        assertThat(snapshot.storeLatencyP95Ms()).isLessThan(5.0);
        assertThat(snapshot.storeLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.storeLatencyP95Ms());
        assertThat(snapshot.storeLatencyP99Ms()).isLessThanOrEqualTo(snapshot.storeLatencyMaxMs());
        assertThat(snapshot.recallLatencyP95Ms()).isLessThan(10.0);
        assertThat(snapshot.recallLatencyP99Ms()).isGreaterThanOrEqualTo(snapshot.recallLatencyP95Ms());
        assertThat(snapshot.recallLatencyP99Ms()).isLessThanOrEqualTo(snapshot.recallLatencyMaxMs());
    }
}
