package com.vortex.kernel.paging;

import com.vortex.common.model.MemoryFragment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticPageTableMetricsBinderTest {

    @Test
    void bindRegistersIncrementalAssignmentMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SemanticPageTable pageTable = new SemanticPageTable(new PrefetchMetricsBinderTest.NoopColdStore(), SemanticPageTable.DEFAULT_PAGE_TABLE_KEY, 0.05);
        pageTable.buildPagesFromFragments(List.of(
                fragment("a", 1.0f, 0.0f),
                fragment("b", 0.99f, 0.01f),
                fragment("c", 0.98f, 0.02f),
                fragment("d", 0.97f, 0.03f)));
        pageTable.buildPagesFromFragments(List.of(fragment("far", 0.0f, 1.0f)));

        SemanticPageTableMetricsBinder binder = new SemanticPageTableMetricsBinder(registry, pageTable);
        binder.bind();

        assertThat(registry.find("vortex.hmc.paging.incremental.assignment.count").gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.paging.incremental.assignment.new.page.rate").gauge()).isNotNull();
    }

    private static MemoryFragment fragment(String id, float x, float y) {
        return MemoryFragment.builder()
                .id(id)
                .namespace("ns")
                .content(id)
                .embedding(new float[]{x, y})
                .build();
    }
}
