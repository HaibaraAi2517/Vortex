package com.vortex.app.eval;

import com.vortex.common.dto.MemoryScenario;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vortex.eval.learning")
public class LearningMemoryEvalProperties {

    private String profileId = "learning-v1-agent-feedback-audit";
    private String datasetLocation = "classpath:llm-memory-eval-set-learning-v1-agent-feedback.json";
    private String datasetVersion = "learning-v1-agent-feedback";
    private boolean writeReport = true;
    private String reportOutputDir = "ops/eval-reports";
    private int defaultTopK = 4;
    private int defaultTokenBudget = 1024;
    private MemoryScenario defaultMemoryScenario = MemoryScenario.CHAT;
    private int minScenarioCount = 5;
    private int minFeedbackSampleCount = 30;
    private double minProbeAllRelevantHitRate = 0.90d;
    private double minProbeAverageNdcg = 0.90d;
    private int minRankImprovedScenarioCount = 0;
    private int minNdcgImprovedScenarioCount = 0;
}
