package com.vortex.kernel.generation;

import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblerTest {

    private final TokenCounter tokenCounter = text -> {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    };

    private final PromptAssembler promptAssembler = new PromptAssembler(tokenCounter, 80);

    @Test
    void assembleShouldKeepFragmentsWithinBudgetAndMarkOmittedOnes() {
        RecallResult recallResult = RecallResult.builder()
                .fragments(List.of(
                        fragment("frag-1", "L1", 0.99, "Rust is Avery's favorite language."),
                        fragment("frag-2", "L2", 0.87, "Avery also uses PostgreSQL in production with logical replication and nightly verification checks."),
                        fragment("frag-3", "L2", 0.83, "Avery's backup hobby is landscape photography with long trips, film scans, and detailed camera notes.")))
                .build();

        PromptAssemblyResult result = promptAssembler.assemble(new PromptAssemblyRequest(
                null,
                "What is Avery's favorite language?",
                recallResult,
                "Answer for a deterministic eval harness.",
                80));

        assertThat(result.includedFragmentIds()).contains("frag-1");
        assertThat(result.omittedFragmentIds()).isNotEmpty();
        assertThat(result.userPrompt()).contains("[fragmentId=frag-1]");
        assertThat(result.promptTokens()).isLessThanOrEqualTo(80);
    }

    private RecallResult.ScoredFragment fragment(String id, String tier, double score, String content) {
        return RecallResult.ScoredFragment.builder()
                .fragment(MemoryFragment.builder()
                        .id(id)
                        .namespace("test")
                        .content(content)
                        .tokenCount(tokenCounter.countTokens(content))
                        .build())
                .tier(tier)
                .score(score)
                .build();
    }
}
