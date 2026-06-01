package com.vortex.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationResult {

    private String content;
    private String model;
    private String requestId;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
    private GenerationLatencyBreakdown latencyBreakdown;

    @Builder.Default
    private Map<String, String> responseMetadata = Map.of();
}
