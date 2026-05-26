package com.vortex.kernel.paging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class PrefetchMetricsBinder {

    private final MeterRegistry meterRegistry;
    private final PrefetchEngine prefetchEngine;

    public PrefetchMetricsBinder(MeterRegistry meterRegistry, PrefetchEngine prefetchEngine) {
        this.meterRegistry = meterRegistry;
        this.prefetchEngine = prefetchEngine;
    }

    @PostConstruct
    public void bind() {
        Gauge.builder("vortex.hmc.paging.prefetch.requested.total", prefetchEngine,
                engine -> engine.getStats().totalRequested())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.prefetch.consumed.total", prefetchEngine,
                engine -> engine.getStats().totalHit())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.prefetch.hit.rate", prefetchEngine,
                engine -> engine.getStats().hitRate())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.prefetch.queue.size", prefetchEngine,
                engine -> engine.getStats().queued())
                .register(meterRegistry);
        Gauge.builder("vortex.hmc.paging.prefetch.inflight.count", prefetchEngine,
                engine -> engine.getStats().inflight())
                .register(meterRegistry);

        for (String source : PrefetchEngine.METRIC_STRATEGY_SOURCES) {
            registerStrategyGauge("vortex.hmc.paging.prefetch.strategy.requested", source,
                    snapshot -> snapshot.requested());
            registerStrategyGauge("vortex.hmc.paging.prefetch.strategy.consumed", source,
                    snapshot -> snapshot.consumed());
            registerStrategyGauge("vortex.hmc.paging.prefetch.strategy.missed", source,
                    snapshot -> snapshot.missed());
            registerStrategyGauge("vortex.hmc.paging.prefetch.strategy.hit.rate", source,
                    snapshot -> snapshot.hitRate());
            registerStrategyGauge("vortex.hmc.paging.prefetch.strategy.effective.budget", source,
                    snapshot -> snapshot.effectiveBudget());
        }
    }

    private void registerStrategyGauge(
            String name,
            String source,
            java.util.function.ToDoubleFunction<PrefetchEngine.StrategySnapshot> extractor) {
        Gauge.builder(name, prefetchEngine, engine -> extractor.applyAsDouble(engine.strategySnapshot(source)))
                .tags(Tags.of("source", source))
                .register(meterRegistry);
    }
}
