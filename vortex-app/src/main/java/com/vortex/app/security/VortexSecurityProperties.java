package com.vortex.app.security;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vortex.security")
public class VortexSecurityProperties {

    private boolean enabled;
    private String bearerToken;
    private List<String> namespacePatterns = new ArrayList<>();
    private long maxRequestBytes = 1_048_576L;
    private int requestsPerMinute = 600;
    private boolean auditEnabled = true;

    @PostConstruct
    void validate() {
        if (!enabled) {
            return;
        }
        if (bearerToken == null || bearerToken.length() < 32) {
            throw new IllegalStateException(
                    "VORTEX_SECURITY_BEARER_TOKEN must contain at least 32 characters when security is enabled");
        }
        namespacePatterns.removeIf(pattern -> pattern == null || pattern.isBlank());
        if (namespacePatterns.isEmpty()) {
            throw new IllegalStateException(
                    "VORTEX_SECURITY_NAMESPACE_PATTERNS must define at least one allowed namespace pattern");
        }
        if (maxRequestBytes < 1_024L) {
            throw new IllegalStateException("vortex.security.max-request-bytes must be at least 1024");
        }
        if (requestsPerMinute < 1) {
            throw new IllegalStateException("vortex.security.requests-per-minute must be positive");
        }
    }
}
