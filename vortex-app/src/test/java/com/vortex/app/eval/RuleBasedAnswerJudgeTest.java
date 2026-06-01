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

    @Test
    void evaluateShouldRequireExpectedAnswerAndMustContainTerms() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "CSV",
                java.util.List.of("quarterly finance exports", "compliance handoff"),
                java.util.List.of(),
                "Quarterly finance exports use CSV according to the reporting policy.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_ANSWER_MISSING_FACT);
        assertThat(judgment.missingMustContain()).containsExactly("compliance handoff");
    }

    @Test
    void evaluateShouldPassWhenExpectedAnswerAndMustContainTermsArePresent() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "CSV",
                java.util.List.of("quarterly finance exports", "compliance handoff"),
                java.util.List.of("XLSX"),
                "Quarterly finance exports use CSV per the compliance handoff.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.failureReason()).isNull();
        assertThat(judgment.missingMustContain()).isEmpty();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldRejectForbiddenTermsBeforeMissingFacts() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "CSV",
                java.util.List.of("compliance handoff"),
                java.util.List.of("XLSX", "PDF"),
                "Quarterly finance exports use XLSX.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_HALLUCINATED_FORBIDDEN_FACT);
        assertThat(judgment.matchedForbiddenTerms()).containsExactly("XLSX");
        assertThat(judgment.missingMustContain()).containsExactly("compliance handoff");
    }

    @Test
    void evaluateShouldRejectInsufficientAnswerWithStructuredReason() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Redshift",
                java.util.List.of("Zephyr risk dashboard"),
                java.util.List.of(),
                "The provided memory is insufficient to determine the warehouse.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_INSUFFICIENT_ANSWER);
        assertThat(judgment.missingMustContain()).containsExactly("Zephyr risk dashboard");
    }

    @Test
    void evaluateShouldUseWordBoundariesForMustContainAndForbiddenTerms() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Rust",
                java.util.List.of("Rust"),
                java.util.List.of("Ruby"),
                "You can trust this answer, but it does not state the language.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_ANSWER_MISSING_FACT);
        assertThat(judgment.missingMustContain()).containsExactly("Rust");
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }
}
