package com.vortex.common.dto;

public enum MemoryScenario {
    CODING,
    CHAT,
    SEARCH;

    public static MemoryScenario fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return CHAT;
        }
        return switch (raw.trim().toLowerCase()) {
            case "coding" -> CODING;
            case "search" -> SEARCH;
            default -> CHAT;
        };
    }
}
