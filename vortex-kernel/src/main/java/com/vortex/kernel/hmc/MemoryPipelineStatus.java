package com.vortex.kernel.hmc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryPipelineStatus {

    private String pipelineId;
    private MemoryPipelineStatusCode status;
    private String namespace;
    private Instant acceptedAt;
    private Instant startedAt;
    private Instant completedAt;
    private List<MemoryPipelineStage> completedStages;
    private List<String> fragmentIds;
    private int extractedUnitCount;
    private int summaryTokenCount;
    private int fragmentCount;
    private String errorMessage;
}
