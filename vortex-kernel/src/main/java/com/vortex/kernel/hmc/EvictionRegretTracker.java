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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

@Slf4j
@Component
public class EvictionRegretTracker {

    private final long regretWindowMs;
    private final long cleanupIntervalMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, EvictedFragmentRecord> recentlyEvicted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RegretProtectionRecord> recentlyRegretted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> evictionsByMode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> regretsByMode = new ConcurrentHashMap<>();
    private final LongAdder evictionCount = new LongAdder();
    private final LongAdder regretCount = new LongAdder();
    private final AtomicLong lastCleanupEpochMs = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public EvictionRegretTracker(
            @Value("${vortex.kernel.eviction.regret-window-ms:3600000}") long regretWindowMs) {
        this(regretWindowMs, System::currentTimeMillis);
    }

    EvictionRegretTracker(long regretWindowMs, LongSupplier clock) {
        this.regretWindowMs = regretWindowMs;
        this.cleanupIntervalMs = computeCleanupIntervalMs(regretWindowMs);
        this.clock = clock;
    }

    public void recordEviction(MemoryFragment fragment, String mode) {
        cleanupExpired(false);
        evictionCount.increment();
        modeCounter(evictionsByMode, mode).increment();
        recentlyEvicted.put(fragment.getId(), new EvictedFragmentRecord(
                fragment.getId(),
                fragment.getNamespace(),
                groupKey(fragment),
                mode,
                clock.getAsLong()
        ));
    }

    public boolean recordRecall(MemoryFragment fragment, String tier) {
        if ("L1".equalsIgnoreCase(tier)) {
            return false;
        }
        cleanupExpired(false);
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
            modeCounter(regretsByMode, evicted.mode()).increment();
            recentlyRegretted.compute(evicted.groupKey(), (key, existing) ->
                    existing == null
                            ? new RegretProtectionRecord(evicted.groupKey(), evicted.mode(), clock.getAsLong(), 1)
                            : existing.refresh(clock.getAsLong()));
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

    public double protectionScore(MemoryFragment fragment) {
        if (fragment == null) {
            return 0.0;
        }
        cleanupExpired(false);
        RegretProtectionRecord protection = recentlyRegretted.get(groupKey(fragment));
        if (protection == null) {
            return 0.0;
        }
        long ageMs = Math.max(0L, clock.getAsLong() - protection.lastRegretAtMs());
        double freshness = 1.0 - Math.min(1.0, ageMs / (double) regretWindowMs);
        double intensity = Math.min(1.0, protection.hits() / 3.0);
        return freshness * Math.max(0.34, intensity);
    }

    public RegretSnapshot snapshot() {
        cleanupExpired(true);
        long evictions = evictionCount.sum();
        long regrets = regretCount.sum();
        double regretRate = evictions == 0 ? 0.0 : (double) regrets / evictions;
        return new RegretSnapshot(
                evictions,
                regrets,
                regretRate,
                recentlyEvicted.size(),
                regretWindowMs,
                recentlyRegretted.size(),
                modeSnapshot());
    }

    private void cleanupExpired(boolean force) {
        long now = clock.getAsLong();
        if (force) {
            lastCleanupEpochMs.set(now);
        }
        if (!force && !shouldRunCleanup(now)) {
            return;
        }
        Iterator<Map.Entry<String, EvictedFragmentRecord>> iterator = recentlyEvicted.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, EvictedFragmentRecord> entry = iterator.next();
            if (now - entry.getValue().evictedAtMs() > regretWindowMs) {
                iterator.remove();
            }
        }
        Iterator<Map.Entry<String, RegretProtectionRecord>> protectionIterator = recentlyRegretted.entrySet().iterator();
        while (protectionIterator.hasNext()) {
            Map.Entry<String, RegretProtectionRecord> entry = protectionIterator.next();
            if (now - entry.getValue().lastRegretAtMs() > regretWindowMs) {
                protectionIterator.remove();
            }
        }
    }

    private boolean shouldRunCleanup(long now) {
        long last = lastCleanupEpochMs.get();
        if (last == Long.MIN_VALUE) {
            return lastCleanupEpochMs.compareAndSet(Long.MIN_VALUE, now);
        }
        if (now - last < cleanupIntervalMs) {
            return false;
        }
        return lastCleanupEpochMs.compareAndSet(last, now);
    }

    private long computeCleanupIntervalMs(long windowMs) {
        long bounded = Math.max(50L, windowMs / 10L);
        return Math.min(1_000L, bounded);
    }

    private Map<String, ModeRegretSnapshot> modeSnapshot() {
        Map<String, ModeRegretSnapshot> snapshot = new java.util.HashMap<>();
        java.util.Set<String> modes = new java.util.HashSet<>(evictionsByMode.keySet());
        modes.addAll(regretsByMode.keySet());
        for (String mode : modes) {
            long evictions = evictionsByMode.getOrDefault(mode, new LongAdder()).sum();
            long regrets = regretsByMode.getOrDefault(mode, new LongAdder()).sum();
            double rate = evictions == 0 ? 0.0 : regrets / (double) evictions;
            snapshot.put(mode, new ModeRegretSnapshot(evictions, regrets, rate));
        }
        return Map.copyOf(snapshot);
    }

    private LongAdder modeCounter(ConcurrentHashMap<String, LongAdder> counters, String mode) {
        String key = (mode == null || mode.isBlank()) ? "unknown" : mode;
        return counters.computeIfAbsent(key, ignored -> new LongAdder());
    }

    private String groupKey(MemoryFragment fragment) {
        if (fragment.getReasoningChainId() == null || fragment.getReasoningChainId().isBlank()) {
            return "__self__:" + fragment.getId();
        }
        return fragment.getReasoningChainId();
    }

    private record EvictedFragmentRecord(
            String fragmentId,
            String namespace,
            String groupKey,
            String mode,
            long evictedAtMs) {
    }

    private record RegretProtectionRecord(
            String groupKey,
            String mode,
            long lastRegretAtMs,
            int hits) {
        private RegretProtectionRecord refresh(long now) {
            return new RegretProtectionRecord(groupKey, mode, now, hits + 1);
        }
    }

    public record RegretSnapshot(
            long evictionCount,
            long regretCount,
            double regretRate,
            int pendingWindowSize,
            long regretWindowMs,
            int protectedGroupCount,
            Map<String, ModeRegretSnapshot> modeBreakdown) {
    }

    public record ModeRegretSnapshot(
            long evictionCount,
            long regretCount,
            double regretRate) {
    }
}
