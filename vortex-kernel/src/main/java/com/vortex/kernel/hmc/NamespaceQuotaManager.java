package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class NamespaceQuotaManager {

    private final double hardQuotaFraction;
    private final double softQuotaFraction;
    private final long minHardQuotaTokens;

    public NamespaceQuotaManager(
            @Value("${vortex.kernel.namespace-quota.hard-fraction:0.25}") double hardQuotaFraction,
            @Value("${vortex.kernel.namespace-quota.soft-fraction:0.15}") double softQuotaFraction,
            @Value("${vortex.kernel.namespace-quota.min-hard-tokens:256}") long minHardQuotaTokens) {
        this.hardQuotaFraction = hardQuotaFraction;
        this.softQuotaFraction = softQuotaFraction;
        this.minHardQuotaTokens = minHardQuotaTokens;
    }

    public QuotaSnapshot snapshot(Collection<MemoryFragment> fragments, long globalCapacity, String focusNamespace) {
        Map<String, Long> namespaceUsage = fragments.stream()
                .filter(fragment -> fragment.getNamespace() != null && !fragment.getNamespace().isBlank())
                .collect(Collectors.groupingBy(
                        MemoryFragment::getNamespace,
                        Collectors.summingLong(MemoryFragment::getTokenCount)));

        long hardQuotaPerNamespace = hardQuotaPerNamespace(globalCapacity, namespaceUsage.size());
        long softQuotaPerNamespace = Math.max(1L, Math.min(hardQuotaPerNamespace, (long) Math.floor(globalCapacity * softQuotaFraction)));

        long protectedTokens = namespaceUsage.values().stream()
                .mapToLong(usage -> Math.min(usage, hardQuotaPerNamespace))
                .sum();
        long reclaimableBorrowedTokens = namespaceUsage.values().stream()
                .mapToLong(usage -> Math.max(0L, usage - hardQuotaPerNamespace))
                .sum();
        long availableBorrowCapacity = Math.max(0L, globalCapacity - protectedTokens);

        long focusUsage = namespaceUsage.getOrDefault(focusNamespace, 0L);
        long focusBorrowed = Math.max(0L, focusUsage - hardQuotaPerNamespace);
        boolean focusAboveSoftQuota = focusUsage > softQuotaPerNamespace;
        boolean focusAboveHardQuota = focusUsage > hardQuotaPerNamespace;

        return new QuotaSnapshot(
                globalCapacity,
                hardQuotaPerNamespace,
                softQuotaPerNamespace,
                protectedTokens,
                availableBorrowCapacity,
                reclaimableBorrowedTokens,
                focusUsage,
                focusBorrowed,
                focusAboveSoftQuota,
                focusAboveHardQuota,
                namespaceUsage
        );
    }

    public long hardQuotaPerNamespace(long globalCapacity, int activeNamespaceCount) {
        int namespaceCount = Math.max(1, activeNamespaceCount);
        long defaultHardQuota =
                Math.max(minHardQuotaTokens, (long) Math.floor(globalCapacity * hardQuotaFraction));
        long fairShareHardQuota = Math.max(1L, globalCapacity / namespaceCount);
        return Math.min(defaultHardQuota, fairShareHardQuota);
    }

    public List<String> evictionPriorityNamespaces(Collection<MemoryFragment> fragments, long globalCapacity, String focusNamespace) {
        QuotaSnapshot snapshot = snapshot(fragments, globalCapacity, focusNamespace);
        return snapshot.namespaceUsage().entrySet().stream()
                .filter(entry -> !Objects.equals(entry.getKey(), focusNamespace))
                .filter(entry -> entry.getValue() > snapshot.hardQuotaPerNamespace())
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(entry ->
                                Math.max(0L, entry.getValue() - snapshot.hardQuotaPerNamespace()))
                        .reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    public record QuotaSnapshot(
            long globalCapacity,
            long hardQuotaPerNamespace,
            long softQuotaPerNamespace,
            long protectedTokens,
            long availableBorrowCapacity,
            long reclaimableBorrowedTokens,
            long focusNamespaceUsage,
            long focusNamespaceBorrowedTokens,
            boolean focusNamespaceAboveSoftQuota,
            boolean focusNamespaceAboveHardQuota,
            Map<String, Long> namespaceUsage) {
    }
}
