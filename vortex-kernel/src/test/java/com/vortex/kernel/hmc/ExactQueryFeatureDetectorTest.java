package com.vortex.kernel.hmc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExactQueryFeatureDetectorTest {

    private final ExactQueryFeatureDetector detector = new ExactQueryFeatureDetector();

    @Test
    void enablesKeywordRetrievalForExactFeatures() {
        assertThat(detector.shouldUseKeyword("What owns issue VTX-42?")).isTrue();
        assertThat(detector.shouldUseKeyword("Open C:\\work\\config.yml")).isTrue();
        assertThat(detector.shouldUseKeyword("Run `mvn -q test`")).isTrue();
        assertThat(detector.shouldUseKeyword("Contact noah@example.com")).isTrue();
    }

    @Test
    void leavesPureNaturalLanguageQueriesOnTheDenseBranch() {
        assertThat(detector.shouldUseKeyword("What did the user prefer last week")).isFalse();
        assertThat(detector.shouldUseKeyword("How many art-related events did I attend")).isFalse();
        assertThat(detector.shouldUseKeyword("Was the pre-approval higher than the sale price")).isFalse();
    }

    @Test
    void assignsFusionWeightByExactFeatureConfidence() {
        assertThat(detector.analyze("Who owns VTX-42").fusionWeight())
                .isEqualTo(ExactQueryFeatureDetector.STRONG_KEYWORD_WEIGHT);
        assertThat(detector.analyze("What happened before the 7/22 trip").fusionWeight())
                .isEqualTo(ExactQueryFeatureDetector.STRUCTURED_NUMBER_WEIGHT);
        assertThat(detector.analyze("Was the price $12").enabled()).isFalse();
    }
}
