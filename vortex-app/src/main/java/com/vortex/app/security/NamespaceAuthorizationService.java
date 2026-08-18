package com.vortex.app.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

@Service
@RequiredArgsConstructor
@Slf4j
public class NamespaceAuthorizationService {

    private final VortexSecurityProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public boolean isEnforced() {
        return properties.isEnabled();
    }

    public void requireAccess(String namespace) {
        if (!canAccess(namespace)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            log.warn(
                    "audit action=namespace-access outcome=denied principal={} namespace={}",
                    authentication == null ? "anonymous" : authentication.getName(),
                    namespace);
            throw new AccessDeniedException("Namespace access denied");
        }
    }

    public boolean canAccess(String namespace) {
        if (!properties.isEnabled()) {
            return true;
        }
        if (namespace == null || namespace.isBlank()) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof VortexPrincipal principal)) {
            return false;
        }
        return principal.namespacePatterns().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, namespace));
    }
}
