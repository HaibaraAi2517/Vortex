package com.vortex.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI vortexOpenApi(@Value("${vortex.release.version}") String version) {
        String schemeName = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("Vortex API")
                        .version(version)
                        .description("REST API for Vortex task DAG orchestration, checkpoint recovery, and hierarchical memory.")
                        .license(new License()
                                .name("Apache License 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
                .servers(java.util.List.of(new Server().url("/").description("Current deployment")))
                .components(new Components().addSecuritySchemes(
                        schemeName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque token")))
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }
}
