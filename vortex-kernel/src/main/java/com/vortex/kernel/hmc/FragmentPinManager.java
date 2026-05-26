package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.l1.CaffeineHotStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages fragment pin lifecycle (pin, unpin, expire).
 * <p>
 * Extracted from {@link HierarchicalMemoryController} to separate
 * pin-state bookkeeping from core orchestration logic.
 */
@Slf4j
@Component
public class FragmentPinManager {

    private final L1HotStore l1;
    private final L2WarmStore l2;
    private final L3ColdStore l3;
    private final FragmentPersistenceManager persistenceManager;
    private TieredEvictionCoordinator evictionCoordinator;
    private final EmbeddingService l1EmbeddingService;
    private final EmbeddingService l2EmbeddingService;

    private final PriorityBlockingQueue<PinnedFragmentRef> pinExpirations = new PriorityBlockingQueue<>();
    private final ConcurrentMap<String, Long> pinnedFragmentDeadlines = new ConcurrentHashMap<>();
    private final AtomicBoolean clearingExpiredPins = new AtomicBoolean(false);
    private final AtomicLong pinnedTokenCount = new AtomicLong(0);

    public FragmentPinManager(
            L1HotStore l1,
            L2WarmStore l2,
            L3ColdStore l3,
            FragmentPersistenceManager persistenceManager,
            @Qualifier("bgeSmallEmbeddingService") EmbeddingService l1EmbeddingService,
            @Qualifier("cloudEmbeddingService") ObjectProvider<EmbeddingService> cloudEmbeddingProvider,
            @Lazy TieredEvictionCoordinator evictionCoordinator) {
        this.l1 = l1;
        this.l2 = l2;
        this.l3 = l3;
        this.persistenceManager = persistenceManager;
        this.l1EmbeddingService = l1EmbeddingService;
        this.l2EmbeddingService = cloudEmbeddingProvider.getIfAvailable();
        this.evictionCoordinator = evictionCoordinator;
    }

    /**
     * Setter for the eviction coordinator; used to resolve the circular
     * dependency during programmatic construction (e.g., unit tests).
     * In a Spring context the {@code @Lazy} constructor parameter
     * handles this automatically.
     */
    void setEvictionCoordinator(TieredEvictionCoordinator evictionCoordinator) {
        if (this.evictionCoordinator == null) {
            // programmatic construction without @Lazy proxy
            this.evictionCoordinator = evictionCoordinator;
        }
    }

    @PostConstruct
    void cleanPinsOnStartup() {
        rebuildPinIndex();
        clearExpiredPins();
    }

    // ---- Public API ----

    public Optional<MemoryFragment> pinFragment(String fragmentId, long ttlMillis) {
        if (fragmentId == null || fragmentId.isBlank() || ttlMillis <= 0) {
            return Optional.empty();
        }
        Optional<MemoryFragment> fragment = this.findFragment(fragmentId);
        fragment.ifPresent(found -> {
            found.pinForMillis(ttlMillis);
            l1.put(found, false);
            this.indexPin(found);
            evictionCoordinator.reindexTierMembership(found);
            persistenceManager.persistAsync(found, "pin-update");
        });
        return fragment;
    }

    public Optional<MemoryFragment> unpinFragment(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) {
            return Optional.empty();
        }
        Optional<MemoryFragment> fragment = this.findFragment(fragmentId);
        fragment.ifPresent(found -> {
            found.unpin();
            l1.put(found, false);
            this.indexPin(found);
            evictionCoordinator.reindexTierMembership(found);
            persistenceManager.persistAsync(found, "pin-update");
        });
        return fragment;
    }

    @Scheduled(fixedDelayString = "${vortex.kernel.pin.cleanup-interval-ms:30000}")
    public void clearExpiredPins() {
        if (!clearingExpiredPins.compareAndSet(false, true)) {
            return;
        }
        try {
        long now = System.currentTimeMillis();
        int cleared = 0;
        while (true) {
            PinnedFragmentRef ref = pinExpirations.peek();
            if (ref == null || ref.pinnedUntilEpochMillis() > now) {
                break;
            }
            pinExpirations.poll();
            Long currentDeadline = pinnedFragmentDeadlines.get(ref.fragmentId());
            if (currentDeadline == null || currentDeadline.longValue() != ref.pinnedUntilEpochMillis()) {
                continue;
            }
            Optional<MemoryFragment> fragment = this.findFragment(ref.fragmentId());
            if (fragment.isEmpty()) {
                pinnedFragmentDeadlines.remove(ref.fragmentId(), currentDeadline);
                continue;
            }
            MemoryFragment found = fragment.get();
            if (!found.clearExpiredPin()) {
                this.indexPin(found);
                continue;
            }
            l1.put(found, false);
            this.indexPin(found);
            evictionCoordinator.reindexTierMembership(found);
            persistenceManager.persistAsync(found, "pin-expired");
            cleared++;
        }
        if (cleared > 0) {
            log.debug("Cleared expired pins count={}", cleared);
        }
        } finally {
            clearingExpiredPins.set(false);
        }
    }

    public long getPinnedTokenCount() {
        return pinnedTokenCount.get();
    }

    // ---- Package-private (used by TieredEvictionCoordinator) ----

    void indexPin(MemoryFragment fragment) {
        Long previousDeadline = pinnedFragmentDeadlines.get(fragment.getId());
        Long pinnedUntil = fragment.getPinnedUntil();
        if (pinnedUntil == null) {
            this.removePinIndex(fragment);
            return;
        }
        if (pinnedUntil <= System.currentTimeMillis()) {
            fragment.clearExpiredPin();
            this.removePinIndex(fragment);
            return;
        }
        if (previousDeadline == null) {
            pinnedTokenCount.addAndGet(fragment.getTokenCount());
        }
        pinnedFragmentDeadlines.put(fragment.getId(), pinnedUntil);
        pinExpirations.offer(new PinnedFragmentRef(fragment.getId(), pinnedUntil));
        trimStalePinEntries(fragment.getId(), pinnedUntil);
    }

    void removePinIndex(MemoryFragment fragment) {
        Long removed = pinnedFragmentDeadlines.remove(fragment.getId());
        if (removed != null) {
            pinnedTokenCount.addAndGet(-fragment.getTokenCount());
        }
    }

    // ---- Package-private findFragment ----

    Optional<MemoryFragment> findFragment(String fragmentId) {
        Optional<MemoryFragment> l1Fragment = l1.peek(fragmentId);
        if (l1Fragment.isPresent()) {
            return l1Fragment;
        }
        Optional<MemoryFragment> archived = l3.retrieveFragment(fragmentId);
        if (archived.isPresent()) {
            MemoryFragment fragment = archived.get();
            fragment.clearExpiredPin();
            if (fragment.getEmbedding() == null) {
                fragment.setEmbedding(l1EmbeddingService.embed(fragment.getContent()));
            }
            if (l2EmbeddingService != null && fragment.getL2Embedding() == null) {
                try {
                    fragment.setL2Embedding(l2EmbeddingService.embed(fragment.getContent()));
                } catch (Exception e) {
                    log.warn("L2 embedding failed in pin lookup, fragment will skip vector indexing fragmentId={}: {}",
                            fragmentId, e.getMessage());
                    fragment.setL2Embedding(null);
                }
            }
            return Optional.of(fragment);
        }
        return l2.get(fragmentId).map(fragment -> {
            if (fragment.getEmbedding() == null) {
                fragment.setEmbedding(l1EmbeddingService.embed(fragment.getContent()));
            }
            return fragment;
        });
    }

    // ---- Private helpers ----

    private void rebuildPinIndex() {
        pinnedTokenCount.set(0L);
        pinnedFragmentDeadlines.clear();
        pinExpirations.clear();
        if (!(l1 instanceof CaffeineHotStore caffeineStore)) {
            return;
        }
        caffeineStore.getAllFragments().forEach(fragment -> {
            indexPin(fragment);
        });
    }

    private void trimStalePinEntries(String fragmentId, long activeDeadline) {
        if (pinExpirations.size() <= pinnedFragmentDeadlines.size() * 4L + 32L) {
            return;
        }
        pinExpirations.removeIf(ref -> ref.fragmentId().equals(fragmentId)
                && ref.pinnedUntilEpochMillis() != activeDeadline);
    }

    // ---- Inner types ----

    public record PinnedFragmentRef(String fragmentId, long pinnedUntilEpochMillis)
            implements Comparable<PinnedFragmentRef> {
        @Override
        public int compareTo(PinnedFragmentRef other) {
            return Long.compare(this.pinnedUntilEpochMillis, other.pinnedUntilEpochMillis);
        }
    }
}
