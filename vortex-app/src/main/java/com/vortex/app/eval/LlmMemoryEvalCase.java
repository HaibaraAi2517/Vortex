package com.vortex.app.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMemoryEvalCase {

    private String caseId;
    private String namespace;

    @Builder.Default
    private List<EvalMemoryFragment> memoryFragments = List.of();

    private String question;
    private String expectedAnswer;

    @Builder.Default
    private List<String> expectedFragments = List.of();

    @Builder.Default
    private List<String> tags = List.of();

    private String difficulty;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvalMemoryFragment {
        private String fragmentId;
        private String content;

        @Builder.Default
        private List<String> tags = List.of();

        private String reasoningChainId;
        private Long pinTtlMillis;
    }
}
