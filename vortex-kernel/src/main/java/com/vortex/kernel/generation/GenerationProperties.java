package com.vortex.kernel.generation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vortex.kernel.generation")
public class GenerationProperties {

    private boolean enabled = false;
    private String baseUrl;
    private String apiKey;
    private String model;
    private double temperature = 0.0d;
    private int maxTokens = 512;
    private Duration timeout = Duration.ofSeconds(30);
    private int maxRetries = 2;
    private Duration retryInitialBackoff = Duration.ofSeconds(1);
    private double retryBackoffMultiplier = 3.0d;
}
