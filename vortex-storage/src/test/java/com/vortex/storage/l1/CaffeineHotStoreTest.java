package com.vortex.storage.l1;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineHotStoreTest {

    @Test
    void putReplacingSameIdKeepsTokenCountAccurate() {
        CaffeineHotStore store = new CaffeineHotStore(100);

        store.put(fragment("same-id", 10));
        store.put(fragment("same-id", 25));

        assertThat(store.currentTokenCount()).isEqualTo(25);
        assertThat(store.namespaceTokenCount("ns")).isEqualTo(25);
        assertThat(store.namespaceFragmentCount("ns")).isEqualTo(1);
        assertThat(store.activeNamespaceCount()).isEqualTo(1);
    }

    @Test
    void removeReliesOnRemovalListenerForSingleTokenDecrement() {
        CaffeineHotStore store = new CaffeineHotStore(100);
        store.put(fragment("to-remove", 12));

        store.remove("to-remove");

        assertThat(store.currentTokenCount()).isZero();
        assertThat(store.namespaceTokenCount("ns")).isZero();
        assertThat(store.namespaceFragmentCount("ns")).isZero();
        assertThat(store.activeNamespaceCount()).isZero();
    }

    @Test
    void metadataOnlyPutOfSameInstanceDoesNotDoubleCountTokens() {
        CaffeineHotStore store = new CaffeineHotStore(100);
        MemoryFragment fragment = fragment("same-instance", 18);
        store.put(fragment);

        store.put(fragment, false);

        assertThat(store.currentTokenCount()).isEqualTo(18);
    }

    @Test
    void sameInstanceTokenMutationUpdatesAccountedWeight() {
        CaffeineHotStore store = new CaffeineHotStore(100);
        MemoryFragment fragment = fragment("same-instance-growth", 18);
        store.put(fragment);

        fragment.setTokenCount(27);
        fragment.setNamespace("other-ns");
        store.put(fragment, false);

        assertThat(store.currentTokenCount()).isEqualTo(27);
        assertThat(store.namespaceTokenCount("ns")).isZero();
        assertThat(store.namespaceTokenCount("other-ns")).isEqualTo(27);
        assertThat(store.namespaceFragmentCount("other-ns")).isEqualTo(1);
        assertThat(store.activeNamespaceCount()).isEqualTo(1);
    }

    @Test
    void concurrentSameIdWritesKeepTokenCountAlignedWithResidentValue() throws Exception {
        CaffeineHotStore store = new CaffeineHotStore(100);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 1; worker <= 8; worker++) {
                int tokenCount = worker;
                String namespace = worker % 2 == 0 ? "ns-even" : "ns-odd";
                futures.add(executor.submit(() -> {
                    for (int iteration = 0; iteration < 500; iteration++) {
                        store.put(fragment(
                                "shared",
                                namespace,
                                tokenCount), false);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        MemoryFragment resident = store.peek("shared").orElseThrow();
        assertThat(store.currentTokenCount()).isEqualTo(resident.getTokenCount());
        assertThat(store.namespaceTokenCount(resident.getNamespace()))
                .isEqualTo(resident.getTokenCount());
        assertThat(store.namespaceFragmentCount(resident.getNamespace())).isEqualTo(1);
        assertThat(store.activeNamespaceCount()).isEqualTo(1);
    }

    @Test
    void physicalStoreDoesNotPassivelyEvictWhenTokenUsageExceedsCapacity() {
        CaffeineHotStore store = new CaffeineHotStore(10);

        store.put(fragment("a", 8));
        store.put(fragment("b", 8));

        assertThat(store.peek("a")).isPresent();
        assertThat(store.peek("b")).isPresent();
        assertThat(store.currentTokenCount()).isEqualTo(16);
    }

    private static MemoryFragment fragment(String id, int tokenCount) {
        return fragment(id, "ns", tokenCount);
    }

    private static MemoryFragment fragment(String id, String namespace, int tokenCount) {
        return MemoryFragment.builder()
                .id(id)
                .namespace(namespace)
                .content("fragment-" + id)
                .tokenCount(tokenCount)
                .build();
    }
}
