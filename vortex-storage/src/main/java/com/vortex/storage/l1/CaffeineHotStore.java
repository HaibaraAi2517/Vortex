package com.vortex.storage.l1;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.model.MemoryFragment;
import com.vortex.storage.api.L1HotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * L1 Hot Store backed by Caffeine.
 *
 * Capacity is measured in tokens (not object count), so large fragments
 * consume proportionally more budget. Physical retention is left to HMC's
 * semantic policy; this store only tracks token usage and object residency.
 */
@Slf4j
@Component
public class CaffeineHotStore implements L1HotStore {

    private final Cache<String, MemoryFragment> cache;
    private final long maxTokens;
    private final AtomicLong currentTokens = new AtomicLong(0);

    /** Called when Caffeine evicts an entry — wired by HMC to push to L2. */
    private BiConsumer<MemoryFragment, RemovalCause> evictionListener = (f, cause) -> {};

    public CaffeineHotStore(
            @Value("${vortex.storage.l1.max-tokens:8192}") long maxTokens) {
        this.maxTokens = maxTokens;
        this.cache = Caffeine.newBuilder()
                // HMC owns capacity enforcement; this listener only guards against
                // future non-explicit Caffeine evictions if cache policy changes again.
                .removalListener((String k, MemoryFragment v, RemovalCause cause) -> {
                    if (v != null) {
                        if (cause.wasEvicted()) {
                            currentTokens.addAndGet(-v.getTokenCount());
                            log.debug("L1 evicted fragment id={} tokens={} cause={}",
                                    v.getId(), v.getTokenCount(), cause);
                            evictionListener.accept(v, cause);
                        }
                    }
                })
                .build();
    }

    /** Register a callback invoked when Caffeine evicts a fragment. */
    public void setEvictionListener(BiConsumer<MemoryFragment, RemovalCause> listener) {
        this.evictionListener = listener;
    }

    @Override
    public void put(MemoryFragment fragment) {
        put(fragment, true);
    }

    @Override
    public void put(MemoryFragment fragment, boolean recordAccess) {
        MemoryFragment existing = cache.getIfPresent(fragment.getId());
        if (recordAccess) {
            fragment.recordAccess();
        }
        if (existing == fragment) {
            return;
        }
        long delta = fragment.getTokenCount() - (existing == null ? 0L : existing.getTokenCount());
        currentTokens.addAndGet(delta);
        cache.put(fragment.getId(), fragment);
    }

    @Override
    public Optional<MemoryFragment> get(String id) {
        MemoryFragment f = cache.getIfPresent(id);
        if (f != null) {
            f.recordAccess();
        }
        return Optional.ofNullable(f);
    }

    @Override
    public Optional<MemoryFragment> peek(String id) {
        return Optional.ofNullable(cache.getIfPresent(id));
    }

    @Override
    public List<MemoryFragment> getAll(String namespace) {
        return cache.asMap().values().stream()
                .filter(f -> namespace.equals(f.getNamespace()))
                .toList();
    }

    public List<MemoryFragment> getAllFragments() {
        return List.copyOf(cache.asMap().values());
    }

    @Override
    public void remove(String id) {
        MemoryFragment existing = cache.getIfPresent(id);
        if (existing != null) {
            currentTokens.addAndGet(-existing.getTokenCount());
        }
        cache.invalidate(id);
    }

    @Override
    public long currentTokenCount() {
        return Math.max(0, currentTokens.get());
    }

    @Override
    public long maxTokenCapacity() {
        return maxTokens;
    }

    @Override
    public void clear(String namespace) {
        List<String> keys = cache.asMap().entrySet().stream()
                .filter(e -> namespace.equals(e.getValue().getNamespace()))
                .map(java.util.Map.Entry::getKey)
                .toList();
        keys.forEach(this::remove);
        log.info("L1 cleared namespace={} removedCount={}", namespace, keys.size());
    }
}
