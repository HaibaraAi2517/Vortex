package com.vortex.app.controller;

import com.vortex.common.model.MemoryFragment;

import java.time.Instant;
import java.util.List;

final class MemoryResponseModels {

    private MemoryResponseModels() {
    }

    static MemoryFragmentResponse from(MemoryFragment fragment) {
        return new MemoryFragmentResponse(
                fragment.getId(),
                fragment.getNamespace(),
                fragment.getContent(),
                fragment.getTokenCount(),
                fragment.getImportance(),
                fragment.getLastAccessTime(),
                fragment.getCreatedAt(),
                fragment.getTags(),
                fragment.getReasoningChainId(),
                fragment.getPinnedUntil(),
                fragment.isPinned());
    }

    record MemoryFragmentResponse(
            String id,
            String namespace,
            String content,
            int tokenCount,
            double importance,
            long lastAccessTime,
            Instant createdAt,
            List<String> tags,
            String reasoningChainId,
            Long pinnedUntil,
            boolean pinned) {
    }
}
