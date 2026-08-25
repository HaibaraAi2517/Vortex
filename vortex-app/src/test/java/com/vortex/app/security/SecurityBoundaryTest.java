package com.vortex.app.security;

import com.vortex.app.controller.TaskExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityBoundaryTest.SecurityTestController.class)
@Import({
        SecurityBoundaryTest.SecurityTestController.class,
        SecurityConfig.class,
        BearerTokenAuthenticationFilter.class,
        ApiRateLimitFilter.class,
        RequestSizeLimitFilter.class,
        NamespaceAuthorizationService.class,
        TaskExceptionHandler.class
})
@EnableConfigurationProperties(VortexSecurityProperties.class)
@TestPropertySource(properties = {
        "vortex.security.enabled=true",
        "vortex.security.bearer-token=0123456789abcdef0123456789abcdef",
        "vortex.security.namespace-patterns[0]=tenant-a/**",
        "vortex.security.max-request-bytes=1024",
        "vortex.security.requests-per-minute=2",
        "vortex.security.audit-enabled=false"
})
class SecurityBoundaryTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedBusinessAndMetricsRequestsShouldBeRejected() throws Exception {
        mockMvc.perform(get("/api/v1/test/tenant-a/project"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerAndOpenApiDocumentsShouldRemainAnonymous() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void basicHealthShouldRemainAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void validBearerTokenShouldAuthorizeAllowedNamespace() throws Exception {
        mockMvc.perform(get("/api/v1/test/tenant-a/project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("tenant-a/project"));
    }

    @Test
    void principalShouldNotAccessNamespaceOutsideItsPatterns() throws Exception {
        mockMvc.perform(get("/api/v1/test/tenant-b/project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.11");
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void apiQuotaShouldReturnStableRateLimitResponse() throws Exception {
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(get("/api/v1/test/tenant-a/project")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .with(candidate -> {
                                candidate.setRemoteAddr("192.0.2.12");
                                return candidate;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/test/tenant-a/project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.12");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void oversizedRequestShouldBeRejectedBeforeControllerInvocation() throws Exception {
        mockMvc.perform(post("/api/v1/test/echo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("x".repeat(1025)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("REQUEST_TOO_LARGE"));
    }

    @Test
    void unhandledFailureShouldNotExposeBackendDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test/fail")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.13");
                            return request;
                        }))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("C:\\secrets\\redis.conf"))))
                .andExpect(content().string(not(containsString("redis.internal"))));
    }

    @RestController
    static class SecurityTestController {

        private final NamespaceAuthorizationService namespaceAuthorization;

        SecurityTestController(NamespaceAuthorizationService namespaceAuthorization) {
            this.namespaceAuthorization = namespaceAuthorization;
        }

        @GetMapping("/api/v1/test/{namespace}/project")
        Map<String, String> namespace(@PathVariable("namespace") String namespace) {
            String qualifiedNamespace = namespace + "/project";
            namespaceAuthorization.requireAccess(qualifiedNamespace);
            return Map.of("namespace", qualifiedNamespace);
        }

        @PostMapping("/api/v1/test/echo")
        String echo(@RequestBody String body) {
            return body;
        }

        @GetMapping("/api/v1/test/fail")
        void fail() {
            throw new IllegalStateException(
                    "Redis redis.internal failed while reading C:\\secrets\\redis.conf");
        }

        @GetMapping("/swagger-ui.html")
        String swagger() {
            return "swagger";
        }

        @GetMapping("/swagger-ui/index.html")
        String swaggerIndex() {
            return "swagger-index";
        }

        @GetMapping("/v3/api-docs")
        String apiDocs() {
            return "api-docs";
        }

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "metrics";
        }

        @GetMapping("/actuator/health")
        Map<String, String> health() {
            return Map.of("status", "UP");
        }
    }
}
