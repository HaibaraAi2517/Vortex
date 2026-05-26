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
    private Integer activeArmIndex;
    private Integer shadowArmIndex;
    private double activeSelectionProbability;
    private double shadowSelectionProbability;
    private List<String> rankedFragmentIds;
    private List<String> shadowRankedFragmentIds;
    private List<String> baselineRankedFragmentIds;
    private List<String> returnedFragmentIds;
    private List<String> activeEvictionRankedFragmentIds;
    private List<String> shadowEvictionRankedFragmentIds;
    private List<String> baselineEvictionRankedFragmentIds;
    private double activeGroundingScore;
    private double shadowGroundingScore;
    private double baselineGroundingScore;
    private Instant createdAt;
}
