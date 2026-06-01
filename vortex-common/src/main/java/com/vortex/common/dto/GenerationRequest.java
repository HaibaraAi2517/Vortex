package com.vortex.common.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationRequest {

    @NotBlank
    private String userPrompt;

    @Builder.Default
    private String systemPrompt = "";

    @Size(max = 128)
    private String model;

    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double temperature;

    @Positive
    private Integer maxTokens;

    @Positive
    private Long timeoutMs;

    @Builder.Default
    private Map<String, String> metadata = Map.of();
}
