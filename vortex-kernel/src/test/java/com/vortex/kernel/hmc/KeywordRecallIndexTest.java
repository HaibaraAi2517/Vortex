package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordRecallIndexTest {

    @Test
    void chineseSentencesShareBigramsAcrossDifferentWording() {
        var target = MemoryFragment.builder().id("coffee").content("我喜欢喝美式咖啡，不加糖").build();
        var noise = MemoryFragment.builder().id("noise").content("明天去公园散步").build();
        assertThat(new KeywordRecallIndex().search("用户喜欢什么咖啡", List.of(noise, target), 5))
                .extracting(hit -> hit.fragment().getId()).containsExactly("coffee");
        assertThat(KeywordRecallIndex.tokenize("我喜欢Java与咖啡")).contains("喜欢", "java", "咖啡");
    }

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
