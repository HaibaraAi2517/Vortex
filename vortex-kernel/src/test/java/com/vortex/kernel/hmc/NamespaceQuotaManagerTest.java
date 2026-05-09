package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceQuotaManagerTest {

    @Test
    void snapshotProtectsHardQuotaForEachNamespace() {
        NamespaceQuotaManager manager = new NamespaceQuotaManager(0.25, 0.15, 20);

        NamespaceQuotaManager.QuotaSnapshot snapshot = manager.snapshot(List.of(
                fragment("a1", "a", 40),
                fragment("b1", "b", 20),
                fragment("b2", "b", 20)
        ), 120, "a");

        assertThat(snapshot.hardQuotaPerNamespace()).isEqualTo(30);
        assertThat(snapshot.softQuotaPerNamespace()).isEqualTo(18);
        assertThat(snapshot.focusNamespaceBorrowedTokens()).isEqualTo(10);
        assertThat(snapshot.reclaimableBorrowedTokens()).isEqualTo(20);
    }

    @Test
    void evictionPriorityOrdersNamespacesByBorrowedUsage() {
        NamespaceQuotaManager manager = new NamespaceQuotaManager(0.25, 0.15, 20);

        List<String> priorities = manager.evictionPriorityNamespaces(List.of(
                fragment("a1", "a", 40),
                fragment("b1", "b", 35),
                fragment("c1", "c", 20)
        ), 120, "a");

        assertThat(priorities).containsExactly("b");
    }

    private static MemoryFragment fragment(String id, String namespace, int tokens) {
        return MemoryFragment.builder()
                .id(id)
                .namespace(namespace)
                .content(id)
                .tokenCount(tokens)
                .build();
    }
}
