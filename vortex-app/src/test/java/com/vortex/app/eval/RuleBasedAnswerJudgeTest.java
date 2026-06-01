package com.vortex.app.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedAnswerJudgeTest {

    private final RuleBasedAnswerJudge judge = new RuleBasedAnswerJudge();

    @Test
    void shouldRejectInsufficientAnswerThatOnlyMentionsExpectedValueAsExample() {
        String generatedAnswer = "The provided memory fragments are **insufficient** to determine which format "
                + "quarterly finance exports must use. No fragments were supplied that specify an export format "
                + "(e.g., CSV, XLSX, PDF, specific schema, etc.).";

        assertThat(judge.isCorrect("CSV", generatedAnswer)).isFalse();
    }

    @Test
    void shouldAcceptExpectedAnswerWhenEmbeddedInGroundedSentence() {
        String generatedAnswer = "Quarterly finance exports must be generated in **CSV format** "
                + "(per memory fragment profile-013::report-format::530e8150).";

        assertThat(judge.isCorrect("CSV", generatedAnswer)).isTrue();
    }

    @Test
    void shouldRejectPartialSubstringMatches() {
        assertThat(judge.isCorrect("Rust", "You can trust this answer.")).isFalse();
    }

    @Test
    void shouldRejectNumericSubstringMatches() {
        assertThat(judge.isCorrect("8443", "The admin endpoint listens on port 18443.")).isFalse();
    }
}
