package com.vortex.storage.l1;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.model.MemoryFragment;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L1HotStoreAdmin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
public class CaffeineHotStore implements L1HotStore, L1HotStoreAdmin {

    private final Cache<String, MemoryFragment> cache;
    private final long maxTokens;
    private final AtomicLong currentTokens = new AtomicLong(0);
    private final ConcurrentMap<String, AccountedAllocation> accountedAllocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, NamespaceAllocation> namespaceAllocations = new ConcurrentHashMap<>();

    /** Called when Caffeine evicts an entry — wired by HMC to push to L2. */
    private BiConsumer<MemoryFragment, EvictionCause> evictionListener = (f, cause) -> {};

    public CaffeineHotStore(
            @Value("${vortex.storage.l1.max-tokens:8192}") long maxTokens) {
        this.maxTokens = maxTokens;
        this.cache = Caffeine.newBuilder()
                // HMC owns capacity enforcement; this listener only guards against
                // future non-explicit Caffeine evictions if cache policy changes again.
                .removalListener((String k, MemoryFragment v, RemovalCause cause) -> {
                    if (v != null) {
                        if (cause.wasEvicted()) {
                            AccountedAllocation removedAllocation = accountedAllocations.remove(k);
                            if (removedAllocation != null) {
                                removeAccounting(removedAllocation);
                            }
                            log.debug("L1 evicted fragment id={} tokens={} cause={}",
                                    v.getId(), v.getTokenCount(), cause);
                            evictionListener.accept(v, mapCause(cause));
                        }
                    }
                })
                .build();
    }

    /** Register a callback invoked when Caffeine evicts a fragment. */
    @Override
    public void registerEvictionListener(BiConsumer<MemoryFragment, EvictionCause> listener) {
        this.evictionListener = listener;
    }

    @Override
    public void put(MemoryFragment fragment) {
        put(fragment, true);
    }

    @Override
    public void put(MemoryFragment fragment, boolean recordAccess) {
        if (recordAccess) {
            fragment.recordAccess();
        }
        cache.asMap().compute(fragment.getId(), (id, existing) -> {
            AccountedAllocation previousAllocation = accountedAllocations.get(id);
            if (previousAllocation == null && existing != null) {
                previousAllocation = AccountedAllocation.of(existing);
            }
            AccountedAllocation nextAllocation = AccountedAllocation.of(fragment);
            replaceAccounting(previousAllocation, nextAllocation);
            accountedAllocations.put(id, nextAllocation);
            return fragment;
        });
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

    @Override
    public List<MemoryFragment> allFragments() {
        return List.copyOf(cache.asMap().values());
    }

    @Override
    public long namespaceTokenCount(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return 0L;
        }
        NamespaceAllocation allocation = namespaceAllocations.get(namespace);
        return allocation == null ? 0L : allocation.tokens();
    }

    @Override
    public int namespaceFragmentCount(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return 0;
        }
        NamespaceAllocation allocation = namespaceAllocations.get(namespace);
        return allocation == null ? 0 : allocation.fragments();
    }

    @Override
    public int activeNamespaceCount() {
        return namespaceAllocations.size();
    }

    @Override
    public void remove(String id) {
        cache.asMap().compute(id, (key, existing) -> {
            AccountedAllocation removedAllocation = accountedAllocations.remove(key);
            if (removedAllocation != null) {
                removeAccounting(removedAllocation);
            }
            return null;
        });
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

    private void replaceAccounting(
            AccountedAllocation previousAllocation,
            AccountedAllocation nextAllocation) {
        long previousTokens = previousAllocation == null ? 0L : previousAllocation.tokens();
        currentTokens.addAndGet(nextAllocation.tokens() - previousTokens);

        if (previousAllocation == null) {
            adjustNamespaceAllocation(nextAllocation.namespace(), nextAllocation.tokens(), 1);
            return;
        }
        if (Objects.equals(previousAllocation.namespace(), nextAllocation.namespace())) {
            adjustNamespaceAllocation(
                    nextAllocation.namespace(),
                    nextAllocation.tokens() - previousAllocation.tokens(),
                    0);
            return;
        }
        adjustNamespaceAllocation(previousAllocation.namespace(), -previousAllocation.tokens(), -1);
        adjustNamespaceAllocation(nextAllocation.namespace(), nextAllocation.tokens(), 1);
    }

    private void removeAccounting(AccountedAllocation allocation) {
        currentTokens.addAndGet(-allocation.tokens());
        adjustNamespaceAllocation(allocation.namespace(), -allocation.tokens(), -1);
    }

    private void adjustNamespaceAllocation(String namespace, long tokenDelta, int fragmentDelta) {
        if (namespace == null || namespace.isBlank()) {
            return;
        }
        namespaceAllocations.compute(namespace, (key, current) -> {
            long currentTokens = current == null ? 0L : current.tokens();
            int currentFragments = current == null ? 0 : current.fragments();
            long nextTokens = currentTokens + tokenDelta;
            int nextFragments = currentFragments + fragmentDelta;
            if (nextFragments < 0 || (nextFragments == 0 && nextTokens != 0L)) {
                throw new IllegalStateException(
                        "Invalid L1 namespace accounting namespace=%s tokens=%d fragments=%d"
                                .formatted(namespace, nextTokens, nextFragments));
            }
            return nextFragments == 0 ? null : new NamespaceAllocation(nextTokens, nextFragments);
        });
    }

    private EvictionCause mapCause(RemovalCause cause) {
        return switch (cause) {
            case SIZE -> EvictionCause.SIZE;
            case EXPIRED -> EvictionCause.EXPIRED;
            case EXPLICIT -> EvictionCause.EXPLICIT;
            case REPLACED -> EvictionCause.REPLACED;
            case COLLECTED -> EvictionCause.COLLECTED;
        };
    }

    private record AccountedAllocation(String namespace, long tokens) {
        private static AccountedAllocation of(MemoryFragment fragment) {
            return new AccountedAllocation(fragment.getNamespace(), fragment.getTokenCount());
        }
    }

    private record NamespaceAllocation(long tokens, int fragments) {
    }
}
