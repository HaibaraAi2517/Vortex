package com.vortex.kernel.snapshot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CheckpointRecoveryMetrics {

    private final MeterRegistry meterRegistry;

    public CheckpointRecoveryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordSuccess(CheckpointRecoveryMode mode) {
        Counter.builder("vortex.checkpoint.recovery.total")
                .tag("outcome", "success")
                .tag("mode", mode.name())
                .register(meterRegistry)
                .increment();
    }

    public void recordFailure(CheckpointRecoveryFailureReason reason) {
        Counter.builder("vortex.checkpoint.recovery.total")
                .tag("outcome", "failure")
                .tag("mode", "NONE")
                .tag("reason", reason.name())
                .register(meterRegistry)
                .increment();
    }
}
