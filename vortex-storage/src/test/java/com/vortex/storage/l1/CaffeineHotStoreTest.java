package com.vortex.storage.l1;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineHotStoreTest {

    @Test
    void putReplacingSameIdKeepsTokenCountAccurate() {
        CaffeineHotStore store = new CaffeineHotStore(100);

        store.put(fragment("same-id", 10));
        store.put(fragment("same-id", 25));

        assertThat(store.currentTokenCount()).isEqualTo(25);
    }

    @Test
    void removeReliesOnRemovalListenerForSingleTokenDecrement() {
        CaffeineHotStore store = new CaffeineHotStore(100);
        store.put(fragment("to-remove", 12));

        store.remove("to-remove");

        assertThat(store.currentTokenCount()).isZero();
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
    void physicalStoreDoesNotPassivelyEvictWhenTokenUsageExceedsCapacity() {
        CaffeineHotStore store = new CaffeineHotStore(10);

        store.put(fragment("a", 8));
        store.put(fragment("b", 8));

        assertThat(store.peek("a")).isPresent();
        assertThat(store.peek("b")).isPresent();
        assertThat(store.currentTokenCount()).isEqualTo(16);
    }

    private static MemoryFragment fragment(String id, int tokenCount) {
        return MemoryFragment.builder()
                .id(id)
                .namespace("ns")
                .content("fragment-" + id)
                .tokenCount(tokenCount)
                .build();
    }
}
