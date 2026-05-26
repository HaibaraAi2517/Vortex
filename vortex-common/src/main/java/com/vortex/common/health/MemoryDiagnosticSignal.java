package com.vortex.common.health;

import java.util.Map;

public record MemoryDiagnosticSignal(
        String code,
        String severity,
        String source,
        String message,
        Map<String, Object> attributes) {

    public MemoryDiagnosticSignal {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
