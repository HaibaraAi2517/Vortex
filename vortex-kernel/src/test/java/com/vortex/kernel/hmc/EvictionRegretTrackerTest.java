package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class EvictionRegretTrackerTest {

    @Test
    void recordRecallFromWarmTierWithinWindowIncrementsRegret() {
        AtomicLong now = new AtomicLong(1_000L);
        EvictionRegretTracker tracker = new EvictionRegretTracker(500L, now::get);
        MemoryFragment fragment = fragment("f-1", "ns");

        tracker.recordEviction(fragment, "semantic");
        boolean counted = tracker.recordRecall(fragment, "L2");

        assertThat(counted).isTrue();
        assertThat(tracker.snapshot().regretCount()).isEqualTo(1);
    }

    @Test
    void expiredEvictionsDoNotCountAsRegret() {
        AtomicLong now = new AtomicLong(1_000L);
        EvictionRegretTracker tracker = new EvictionRegretTracker(100L, now::get);
        MemoryFragment fragment = fragment("f-2", "ns");

        tracker.recordEviction(fragment, "semantic");
        now.set(1_500L);
        boolean counted = tracker.recordRecall(fragment, "L2");

        assertThat(counted).isFalse();
        assertThat(tracker.snapshot().regretCount()).isZero();
        assertThat(tracker.snapshot().pendingWindowSize()).isZero();
    }

    @Test
    void l1RecallDoesNotCountAsRegret() {
        EvictionRegretTracker tracker = new EvictionRegretTracker(500L, System::currentTimeMillis);
        MemoryFragment fragment = fragment("f-3", "ns");

        tracker.recordEviction(fragment, "semantic");

        assertThat(tracker.recordRecall(fragment, "L1")).isFalse();
        assertThat(tracker.snapshot().regretCount()).isZero();
    }

    private static MemoryFragment fragment(String id, String namespace) {
        return MemoryFragment.builder()
                .id(id)
                .namespace(namespace)
                .content("content-" + id)
                .tokenCount(5)
                .build();
    }
}
