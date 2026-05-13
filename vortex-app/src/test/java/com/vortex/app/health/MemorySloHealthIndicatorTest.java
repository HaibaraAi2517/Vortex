package com.vortex.app.health;

import com.vortex.kernel.hmc.MemorySloTracker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySloHealthIndicatorTest {

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
                12.0,
                11.0,
                11.5,
                13.0,
                12.0,
                12.5,
                0,
                0,
                0),
                1.0,
                0.05,
                0,
                1.0,
                10.0,
                10.0,
                -0.05,
                0.20,
                0.90);

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
                5.0,
                4.0,
                5.0,
                6.0,
                5.5,
                6.0,
                0,
                0,
                0),
                1.0,
                0.05,
                0,
                1.0,
                10.0,
                10.0,
                -0.05,
                0.20,
                0.90);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
