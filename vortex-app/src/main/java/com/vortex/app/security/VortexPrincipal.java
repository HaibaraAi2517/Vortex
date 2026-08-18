package com.vortex.app.security;

import java.security.Principal;
import java.util.List;

public record VortexPrincipal(String name, List<String> namespacePatterns) implements Principal {

    public VortexPrincipal {
        namespacePatterns = List.copyOf(namespacePatterns);
    }

    @Override
    public String getName() {
        return name;
    }
}
