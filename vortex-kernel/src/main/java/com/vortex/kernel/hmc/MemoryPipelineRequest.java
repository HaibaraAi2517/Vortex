package com.vortex.kernel.hmc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryPipelineRequest {

    private String pipelineId;
    private String content;
    private String namespace;
    private List<String> tags;
    private String reasoningChainId;
    private Long pinTtlMillis;
}
