package com.vortex.common.serialization;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared WAL payload codec for simple string map payloads.
 */
public final class WalPayloads {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private WalPayloads() {
    }

    public static String jsonPayload(String... keyValues) {
        if ((keyValues.length & 1) != 0) {
            throw new IllegalArgumentException("jsonPayload requires an even number of key/value arguments");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1] != null ? keyValues[i + 1] : "");
        }
        try {
            return JsonMapperFactory.shared().writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize WAL payload", e);
        }
    }

    public static Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return JsonMapperFactory.shared().readValue(json, STRING_MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse WAL payload", e);
        }
    }
}
