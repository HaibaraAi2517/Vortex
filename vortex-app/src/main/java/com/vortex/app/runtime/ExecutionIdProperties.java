package com.vortex.app.runtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vortex.runtime.execution-id")
public class ExecutionIdProperties {

    private Backend backend = Backend.MEMORY;
    private Duration ttl = Duration.ofHours(24);
    private String keyPrefix = "vortex:execution-id:";

    public enum Backend {
        MEMORY,
        REDIS
    }
}
