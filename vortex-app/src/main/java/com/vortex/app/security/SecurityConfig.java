package com.vortex.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final VortexSecurityProperties properties;
    private final BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter;
    private final ApiRateLimitFilter apiRateLimitFilter;
    private final ObjectMapper objectMapper;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (properties.isEnabled()) {
            http.authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(
                                    "/actuator/health",
                                    "/actuator/health/liveness",
                                    "/actuator/health/readiness",
                                    "/swagger-ui.html",
                                    "/swagger-ui/**",
                                    "/v3/api-docs",
                                    "/v3/api-docs/**")
                            .permitAll()
                            .anyRequest().authenticated())
                    .addFilterBefore(bearerTokenAuthenticationFilter, AnonymousAuthenticationFilter.class)
                    .addFilterAfter(apiRateLimitFilter, BearerTokenAuthenticationFilter.class)
                    .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, ex) -> {
                        response.setStatus(401);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        objectMapper.writeValue(response.getOutputStream(), Map.of(
                                "error", "AUTHENTICATION_REQUIRED",
                                "message", "A valid Bearer token is required"));
                    }));
        } else {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        }
        return http.build();
    }

    @Bean
    FilterRegistrationBean<BearerTokenAuthenticationFilter> bearerTokenFilterRegistration(
            BearerTokenAuthenticationFilter filter) {
        FilterRegistrationBean<BearerTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiRateLimitFilter> apiRateLimitFilterRegistration(ApiRateLimitFilter filter) {
        FilterRegistrationBean<ApiRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
