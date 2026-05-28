package com.vortex.common.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared Jackson mapper factory to keep module-level JSON behavior aligned.
 */
public final class JsonMapperFactory {

    private static final ObjectMapper SHARED = new ObjectMapper().findAndRegisterModules();

    private JsonMapperFactory() {
    }

    public static ObjectMapper shared() {
        return SHARED;
    }

    public static ObjectMapper create() {
        return SHARED.copy();
    }
}
