package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticTextSplitterTest {

    private final SemanticTextSplitter splitter = new SemanticTextSplitter(
            text -> text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length,
            50
    );

    @Test
    void split_shortText_returnsSingleFragment() {
        List<MemoryFragment> result = splitter.split("Hello world.", "ns", List.of(), null, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("Hello world.");
    }

    @Test
    void split_multipleParagraphs_returnsMultipleFragments() {
        String text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.";
        List<MemoryFragment> result = splitter.split(text, "ns", List.of(), null, null);
        assertThat(result).hasSize(3);
    }

    @Test
    void split_fragmentsHaveCorrectNamespace() {
        List<MemoryFragment> result = splitter.split("Some text.", "my-ns", List.of("tag1"), "chain-1", 10_000L);
        assertThat(result).allMatch(f -> "my-ns".equals(f.getNamespace()));
        assertThat(result).allMatch(f -> f.getTags().contains("tag1"));
        assertThat(result).allMatch(f -> "chain-1".equals(f.getReasoningChainId()));
        assertThat(result).allMatch(MemoryFragment::isPinned);
    }

    @Test
    void split_tokenCountIsPositive() {
        List<MemoryFragment> result = splitter.split("Token counting test.", "ns", List.of(), null, null);
        assertThat(result).allMatch(f -> f.getTokenCount() > 0);
    }

    @Test
    void countTokens_usesConfiguredCounter() {
        assertThat(splitter.countTokens("Hello world")).isEqualTo(2);
    }
}
