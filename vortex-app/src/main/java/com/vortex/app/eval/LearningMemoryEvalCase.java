package com.vortex.app.eval;

import com.vortex.common.dto.MemoryScenario;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningMemoryEvalCase {

    private String scenarioId;
    private String namespace;

    @Builder.Default
    private MemoryScenario memoryScenario = MemoryScenario.CHAT;

    private Integer topK;
    private Integer tokenBudget;

    @Builder.Default
    private List<String> tags = List.of();

    @Builder.Default
    private List<LearningMemoryFragment> fragments = List.of();

    @Builder.Default
    private List<String> calibrationQueries = List.of();

    @Builder.Default
    private List<String> probeQueries = List.of();

    @Builder.Default
    private FeedbackSpec feedback = FeedbackSpec.builder().build();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LearningMemoryFragment {
        private String fragmentId;
        private String content;

        @Builder.Default
        private boolean relevant = false;

        @Builder.Default
        private List<String> tags = List.of();

        private String reasoningChainId;
        private Long pinTtlMillis;
        private Double importance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackSpec {

        @Builder.Default
        private List<String> usedFragmentIds = List.of();

        @Builder.Default
        private boolean answerAccepted = true;

        private Double regretRate;
    }
}
