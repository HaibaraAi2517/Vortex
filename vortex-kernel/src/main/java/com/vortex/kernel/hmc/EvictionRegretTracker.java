package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

@Slf4j
@Component
public class EvictionRegretTracker {

    private final long regretWindowMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, EvictedFragmentRecord> recentlyEvicted = new ConcurrentHashMap<>();
    private final LongAdder evictionCount = new LongAdder();
    private final LongAdder regretCount = new LongAdder();

    @Autowired
    public EvictionRegretTracker(
            @Value("${vortex.kernel.eviction.regret-window-ms:3600000}") long regretWindowMs) {
        this(regretWindowMs, System::currentTimeMillis);
    }

    EvictionRegretTracker(long regretWindowMs, LongSupplier clock) {
        this.regretWindowMs = regretWindowMs;
        this.clock = clock;
    }

    public void recordEviction(MemoryFragment fragment, String mode) {
        cleanupExpired();
        evictionCount.increment();
        recentlyEvicted.put(fragment.getId(), new EvictedFragmentRecord(
                fragment.getId(),
                fragment.getNamespace(),
                mode,
                clock.getAsLong()
        ));
    }

    public boolean recordRecall(MemoryFragment fragment, String tier) {
        if ("L1".equalsIgnoreCase(tier)) {
            return false;
        }
        cleanupExpired();
        EvictedFragmentRecord evicted = recentlyEvicted.get(fragment.getId());
        if (evicted == null) {
            return false;
        }
        if (!evicted.namespace().equals(fragment.getNamespace())) {
            return false;
        }
        if (clock.getAsLong() - evicted.evictedAtMs() > regretWindowMs) {
            recentlyEvicted.remove(fragment.getId(), evicted);
            return false;
        }
        if (recentlyEvicted.remove(fragment.getId(), evicted)) {
            regretCount.increment();
            log.warn("eviction-regret fragmentId={} namespace={} tier={} evictedAt={} recallAt={} mode={}",
                    fragment.getId(),
                    fragment.getNamespace(),
                    tier,
                    Instant.ofEpochMilli(evicted.evictedAtMs()),
                    Instant.ofEpochMilli(clock.getAsLong()),
                    evicted.mode());
            return true;
        }
        return false;
    }

    public RegretSnapshot snapshot() {
        cleanupExpired();
        long evictions = evictionCount.sum();
        long regrets = regretCount.sum();
        double regretRate = evictions == 0 ? 0.0 : (double) regrets / evictions;
        return new RegretSnapshot(evictions, regrets, regretRate, recentlyEvicted.size(), regretWindowMs);
    }

    private void cleanupExpired() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, EvictedFragmentRecord>> iterator = recentlyEvicted.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, EvictedFragmentRecord> entry = iterator.next();
            if (now - entry.getValue().evictedAtMs() > regretWindowMs) {
                iterator.remove();
            }
        }
    }

    private record EvictedFragmentRecord(
            String fragmentId,
            String namespace,
            String mode,
            long evictedAtMs) {
    }

    public record RegretSnapshot(
            long evictionCount,
            long regretCount,
            double regretRate,
            int pendingWindowSize,
            long regretWindowMs) {
    }
}
