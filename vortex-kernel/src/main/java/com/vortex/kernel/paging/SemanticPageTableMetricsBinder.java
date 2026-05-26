package com.vortex.kernel.paging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class SemanticPageTableMetricsBinder {

    private final MeterRegistry meterRegistry;
    private final SemanticPageTable pageTable;

    public SemanticPageTableMetricsBinder(MeterRegistry meterRegistry, SemanticPageTable pageTable) {
        this.meterRegistry = meterRegistry;
        this.pageTable = pageTable;
    }

    @PostConstruct
    public void bind() {
        Gauge.builder("vortex.hmc.paging.page.count", pageTable, table -> table.allPages().size())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.incremental.assignment.count", pageTable,
                table -> table.assignmentStats().incrementalAssignments())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.incremental.assignment.reuse.count", pageTable,
                table -> table.assignmentStats().incrementalReuseAssignments())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.incremental.assignment.new.page.count", pageTable,
                table -> table.assignmentStats().incrementalNewPageAssignments())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.incremental.assignment.reuse.rate", pageTable,
                table -> table.assignmentStats().incrementalReuseRatio())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.incremental.assignment.new.page.rate", pageTable,
                table -> table.assignmentStats().incrementalNewPageRatio())
                .register(meterRegistry);
    }
}
