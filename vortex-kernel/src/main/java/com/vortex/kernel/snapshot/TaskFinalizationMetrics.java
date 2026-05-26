package com.vortex.kernel.snapshot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TaskFinalizationMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger pendingFinalizationTasks = new AtomicInteger();
    private final AtomicInteger pendingCleanupTasks = new AtomicInteger();

    public TaskFinalizationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        Gauge.builder("vortex.task.finalization.pending", pendingFinalizationTasks, AtomicInteger::get)
                .register(meterRegistry);
        Gauge.builder("vortex.task.finalization.cleanup.pending", pendingCleanupTasks, AtomicInteger::get)
                .register(meterRegistry);
    }

    public void setPendingFinalizationCount(int count) {
        pendingFinalizationTasks.set(Math.max(0, count));
    }

    public void setPendingCleanupCount(int count) {
        pendingCleanupTasks.set(Math.max(0, count));
    }

    public void recordPendingFinalizationEntered() {
        Counter.builder("vortex.task.finalization.transitions.total")
                .tag("phase", "pending_finalization")
                .register(meterRegistry)
                .increment();
    }

    public void recordPendingCleanupEntered() {
        Counter.builder("vortex.task.finalization.transitions.total")
                .tag("phase", "pending_cleanup")
                .register(meterRegistry)
                .increment();
    }
}
