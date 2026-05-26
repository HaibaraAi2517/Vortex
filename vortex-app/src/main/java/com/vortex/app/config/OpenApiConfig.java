package com.vortex.app.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Vortex API",
                version = "v1",
                description = "REST API for Vortex task DAG orchestration, checkpoint recovery, and hierarchical memory.",
                license = @License(name = "MIT")),
        servers = @Server(url = "/", description = "Current deployment"))
public class OpenApiConfig {
}
