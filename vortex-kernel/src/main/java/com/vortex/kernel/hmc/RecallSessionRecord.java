package com.vortex.kernel.hmc;

import com.vortex.common.dto.MemoryScenario;
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
public class RecallSessionRecord {

    private String sessionId;
    private String namespace;
    private MemoryScenario scenario;
    private String activeProfileName;
    private String shadowProfileName;
    private List<String> rankedFragmentIds;
    private List<String> shadowRankedFragmentIds;
    private List<String> baselineRankedFragmentIds;
    private Instant createdAt;
}
