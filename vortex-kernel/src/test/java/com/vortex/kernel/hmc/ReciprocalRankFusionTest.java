package com.vortex.kernel.hmc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    @Test
    void keywordMagnitudeDoesNotChangeRankFusionResult() {
        List<String> candidates = List.of("semantic", "exact");
        Map<String, Double> semanticScores = Map.of("semantic", 0.9d, "exact", 0.8d);

        Map<String, Double> ordinaryKeywordScale = fusion.fuse(
                candidates,
                semanticScores,
                Map.of("exact", 1.0d),
                true);
        Map<String, Double> inflatedKeywordScale = fusion.fuse(
                candidates,
                semanticScores,
                Map.of("exact", 10_000.0d),
                true);

        assertThat(inflatedKeywordScale).containsExactlyEntriesOf(ordinaryKeywordScale);
        assertThat(inflatedKeywordScale.get("exact")).isGreaterThan(inflatedKeywordScale.get("semantic"));
        assertThat(inflatedKeywordScale.values()).allMatch(score -> score >= 0.0d && score <= 1.0d);
    }

    @Test
    void disabledKeywordBranchPreservesVectorRank() {
        Map<String, Double> fused = fusion.fuse(
                List.of("first", "second"),
                Map.of("first", 0.8d, "second", 0.7d),
                Map.of("second", 100.0d),
                false);

        assertThat(fused.get("first")).isGreaterThan(fused.get("second"));
        assertThat(fused).containsEntry("first", 0.8d).containsEntry("second", 0.7d);
    }

    @Test
    void weakNumericSignalCannotOvertakeAClearlyStrongerVectorCandidate() {
        List<String> candidates = List.of("first", "middle", "numeric");
        Map<String, Double> semanticScores = Map.of(
                "first", 0.9d,
                "middle", 0.85d,
                "numeric", 0.8d);

        Map<String, Double> weakFusion = fusion.fuse(
                candidates,
                semanticScores,
                Map.of("numeric", 10.0d),
                0.01d);
        Map<String, Double> strongFusion = fusion.fuse(
                candidates,
                semanticScores,
                Map.of("numeric", 10.0d),
                ExactQueryFeatureDetector.STRONG_KEYWORD_WEIGHT);

        assertThat(weakFusion.get("first")).isGreaterThan(weakFusion.get("numeric"));
        assertThat(strongFusion.get("numeric")).isGreaterThan(strongFusion.get("first"));
    }
}
