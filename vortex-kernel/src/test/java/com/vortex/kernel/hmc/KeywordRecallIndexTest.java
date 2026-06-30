package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordRecallIndexTest {

    private final KeywordRecallIndex index = new KeywordRecallIndex();

    @Test
    void searchRanksExactLexicalOverlapAboveDistractor() {
        MemoryFragment target = MemoryFragment.builder()
                .id("target")
                .content("Pegasus release owner is avery-deploy@example.com")
                .tags(List.of("release", "contact"))
                .build();
        MemoryFragment distractor = MemoryFragment.builder()
                .id("distractor")
                .content("General deployment notes mention a release checklist.")
                .tags(List.of("release"))
                .build();

        List<KeywordRecallIndex.KeywordCandidate> results = index.search(
                "Who owns Pegasus release contact avery-deploy@example.com?",
                List.of(distractor, target),
                2);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().fragment().getId()).isEqualTo("target");
        assertThat(results.getFirst().matchedTerms()).contains("pegasus", "release");
    }
}
