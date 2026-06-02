package com.vortex.app.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

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
                List.of("quarterly finance exports", "compliance handoff"),
                List.of(),
                "Quarterly finance exports use CSV according to the reporting policy.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_ANSWER_MISSING_FACT);
        assertThat(judgment.missingMustContain()).containsExactly("compliance handoff");
    }

    @Test
    void evaluateShouldPassWhenExpectedAnswerAndMustContainTermsArePresent() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "CSV",
                List.of("quarterly finance exports", "compliance handoff"),
                List.of("XLSX"),
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
                List.of("compliance handoff"),
                List.of("XLSX", "PDF"),
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
                List.of("Zephyr risk dashboard"),
                List.of(),
                "The provided memory is insufficient to determine the warehouse.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_INSUFFICIENT_ANSWER);
        assertThat(judgment.missingMustContain()).containsExactly("Zephyr risk dashboard");
    }

    @Test
    void evaluateShouldUseWordBoundariesForMustContainAndForbiddenTerms() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Rust",
                List.of("Rust"),
                List.of("Ruby"),
                "You can trust this answer, but it does not state the language.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_ANSWER_MISSING_FACT);
        assertThat(judgment.missingMustContain()).containsExactly("Rust");
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermInNotContext() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "bullet points",
                List.of("incident brief"),
                List.of("narrative paragraph"),
                "Use bullet points for the incident brief, not Ravi's short narrative paragraph.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermInCurlyApostropheNotContext() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "bullet points",
                List.of("incident brief"),
                List.of("narrative paragraph"),
                "Use bullet points for the incident brief, not Ravi\u2019s short narrative paragraph style.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermInInsteadOfContext() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "bullet points",
                List.of("incident brief"),
                List.of("narrative paragraph"),
                "Use bullet points for the incident brief instead of narrative paragraph.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermInRatherThanContext() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "bullet points",
                List.of("incident brief"),
                List.of("narrative paragraph"),
                "Use bullet points for the incident brief rather than narrative paragraph.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldStillRejectAffirmedForbiddenTerm() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "bullet points",
                List.of("incident brief"),
                List.of("narrative paragraph"),
                "Use bullet points and a narrative paragraph for the incident brief.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_HALLUCINATED_FORBIDDEN_FACT);
        assertThat(judgment.matchedForbiddenTerms()).containsExactly("narrative paragraph");
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermWhenDescribedAsPreviousPreference() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "avery-deploy@example.com",
                List.of("email"),
                List.of("Slack"),
                "Deployment alerts for Avery should be sent by email to avery-deploy@example.com; "
                        + "Slack was only a previous preference.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldAcceptEquivalentTimeRangeWithFromConnector() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Sunday 03:00 to 04:00 UTC",
                List.of("Sunday 03:00 to 04:00 UTC"),
                List.of("Saturday 22:00 UTC"),
                "Pager maintenance is allowed only on Sunday from 03:00 to 04:00 UTC.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.missingMustContain()).isEmpty();
    }

    @Test
    void evaluateShouldAcceptBulletPointFormatAsBulletPoints() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "bullet points",
                List.of("bullet points"),
                List.of("narrative paragraph"),
                "The launch incident brief should use Nina's preferred incident summary style, "
                        + "which means a bullet-point format rather than Ravi's style.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.missingMustContain()).isEmpty();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermWhenDescribedAsPreRenewalState() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Platinum",
                List.of("Platinum"),
                List.of("Gold"),
                "Aria is on the Platinum support tier now. The Gold tier is explicitly a previous, pre-renewal state.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermWhenHistoricalReferenceIsExplained() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Platinum",
                List.of("Platinum"),
                List.of("Gold"),
                "Aria is on the Platinum support tier now. The Gold tier reference is explicitly historical, "
                        + "from before contract renewal.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermWhenHistoricalNoteIsFromBeforeRenewal() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Platinum",
                List.of("Platinum"),
                List.of("Gold"),
                "Aria is on the Platinum support tier now. Aria maps to customer C-7319, and that customer "
                        + "is on the Platinum tier; the Gold tier note is explicitly from before contract renewal.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTierWhenOnlyNotedAsBeforeRenewal() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Platinum",
                List.of("Platinum"),
                List.of("Gold"),
                "Aria is on the Platinum support tier. The memory says Aria maps to customer id C-7319, "
                        + "and C-7319 is on the Platinum support tier; the Gold tier was only noted as the tier "
                        + "before contract renewal.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermWhenDescribedAsPreRenewalTier() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "Platinum",
                List.of("Platinum"),
                List.of("Gold"),
                "Aria is on the Platinum support tier now. The Gold tier is explicitly described as the pre-renewal tier.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldStillRejectForbiddenTermWhenCurrentAnswerUsesItBeforeHistoryContext() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "avery-deploy@example.com",
                List.of("email"),
                List.of("Slack"),
                "Send deployment alerts to Slack; email was only a previous preference.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_HALLUCINATED_FORBIDDEN_FACT);
        assertThat(judgment.matchedForbiddenTerms()).containsExactly("Slack");
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermWhenExplicitlyPreviousPreference() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "avery-deploy@example.com",
                List.of("avery-deploy@example.com", "email"),
                List.of("Slack"),
                "Deployment alerts for Avery should now be sent by email to avery-deploy@example.com. "
                        + "Avery previously asked for Slack alerts, but the current preference is email.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenTermWhenOldStatusIsNoLongerCurrent() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "yes",
                List.of("yes"),
                List.of("blocked on copy review"),
                "Yes. The dashboard beta can ship according to the latest remembered status. "
                        + "It was no longer blocked on copy review once accessibility signoff was completed.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenOldStatusWhenFromLastWeek() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "yes",
                List.of("yes"),
                List.of("blocked on copy review"),
                "Yes. The dashboard beta can ship according to the latest remembered status, because it was set "
                        + "to ship only after accessibility signoff, and that accessibility signoff is now complete. "
                        + "The note that it was blocked on copy review was from last week, so it does not override "
                        + "the current status.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldNotRejectForbiddenOldStatusWhenExplicitlyFromLastWeek() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "yes",
                List.of("yes"),
                List.of("blocked on copy review"),
                "Yes, the dashboard beta can ship according to the latest remembered status, because Monday's "
                        + "standup said it should ship only after accessibility signoff, and accessibility signoff "
                        + "is now complete. The note that it was blocked on copy review was explicitly from last week "
                        + "and does not override the current status.");

        assertThat(judgment.correct()).isTrue();
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }

    @Test
    void evaluateShouldStillRejectMissingMustContainWhenForbiddenTermIsNegated() {
        RuleBasedAnswerJudge.Judgment judgment = judge.evaluate(
                "CSV",
                List.of("compliance handoff"),
                List.of("PDF"),
                "Use CSV, not PDF.");

        assertThat(judgment.correct()).isFalse();
        assertThat(judgment.failureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_ANSWER_MISSING_FACT);
        assertThat(judgment.missingMustContain()).containsExactly("compliance handoff");
        assertThat(judgment.matchedForbiddenTerms()).isEmpty();
    }
}
