package com.vortex.app.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedAnswerJudge {

    public static final String FAILURE_ANSWER_MISSING_FACT = "answer_missing_fact";
    public static final String FAILURE_HALLUCINATED_FORBIDDEN_FACT = "hallucinated_forbidden_fact";
    public static final String FAILURE_INSUFFICIENT_ANSWER = "insufficient_answer";

    private static final Pattern INSUFFICIENT_ANSWER_PATTERN = Pattern.compile(
            "\\b(insufficient|unknown)\\b"
                    + "|\\b(?:cannot|can not|can't|cant|could not|couldn't)\\s+"
                    + "(?:determine|answer|tell|identify|infer|say)\\b"
                    + "|\\b(?:do not|don't|dont|did not|didn't)\\s+have\\b"
                    + "|\\bno\\s+(?:memory|fragments|information|details|context)\\b"
                    + "|\\bnone\\s+were\\s+(?:supplied|provided|given)\\b"
                    + "|\\bwithout\\s+additional\\s+(?:context|information|memory)\\b");
    private static final Pattern NEGATED_FORBIDDEN_PREFIX_PATTERN = Pattern.compile(
            "(?:^|[^\\p{Alnum}])(?:not\\s+(?!only\\b)|instead\\s+of\\s+|rather\\s+than\\s+)"
                    + "(?:[\\p{Alnum}'-]+\\s+){0,6}$");

    public boolean isCorrect(String expectedAnswer, String generatedAnswer) {
        return evaluate(expectedAnswer, List.of(), List.of(), generatedAnswer).correct();
    }

    public Judgment evaluate(
            String expectedAnswer,
            List<String> mustContain,
            List<String> mustNotContain,
            String generatedAnswer) {
        if (isBlank(expectedAnswer) || isBlank(generatedAnswer)) {
            return Judgment.incorrect(
                    FAILURE_ANSWER_MISSING_FACT,
                    requiredTerms(expectedAnswer, mustContain),
                    List.of());
        }
        List<String> safeMustContain = safeList(mustContain);
        List<String> safeMustNotContain = safeList(mustNotContain);
        String normalizedExpected = normalize(expectedAnswer);
        String normalizedGenerated = normalize(generatedAnswer);

        List<String> matchedForbiddenTerms = safeMustNotContain.stream()
                .filter(term -> containsForbiddenTerm(normalize(term), normalizedGenerated))
                .toList();
        if (!matchedForbiddenTerms.isEmpty()) {
            return Judgment.incorrect(
                    FAILURE_HALLUCINATED_FORBIDDEN_FACT,
                    missingRequiredTerms(safeMustContain, normalizedGenerated),
                    matchedForbiddenTerms);
        }

        if (containsInsufficientCue(normalizedGenerated)) {
            return Judgment.incorrect(
                    FAILURE_INSUFFICIENT_ANSWER,
                    missingRequiredTerms(safeMustContain, normalizedGenerated),
                    List.of());
        }

        List<String> missingMustContain = missingRequiredTerms(safeMustContain, normalizedGenerated);
        boolean expectedAnswerPresent = normalizedGenerated.equals(normalizedExpected)
                || containsExpectedAnswerPhrase(normalizedExpected, normalizedGenerated);
        if (!missingMustContain.isEmpty() || !expectedAnswerPresent) {
            return Judgment.incorrect(FAILURE_ANSWER_MISSING_FACT, missingMustContain, List.of());
        }

        return new Judgment(true, null, List.of(), List.of());
    }

    private boolean containsInsufficientCue(String normalizedGenerated) {
        return INSUFFICIENT_ANSWER_PATTERN.matcher(normalizedGenerated).find();
    }

    private boolean containsExpectedAnswerPhrase(String normalizedExpected, String normalizedGenerated) {
        Pattern expectedPattern = Pattern.compile("(^|[^\\p{Alnum}])"
                + Pattern.quote(normalizedExpected)
                + "([^\\p{Alnum}]|$)");
        return expectedPattern.matcher(normalizedGenerated).find();
    }

    private boolean containsTerm(String normalizedTerm, String normalizedGenerated) {
        if (isBlank(normalizedTerm)) {
            return false;
        }
        return termPattern(normalizedTerm).matcher(normalizedGenerated).find();
    }

    private boolean containsForbiddenTerm(String normalizedTerm, String normalizedGenerated) {
        if (isBlank(normalizedTerm)) {
            return false;
        }
        Matcher matcher = termPattern(normalizedTerm).matcher(normalizedGenerated);
        while (matcher.find()) {
            if (!hasNegatedForbiddenContext(normalizedGenerated, matcher.start(2))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNegatedForbiddenContext(String normalizedGenerated, int termStart) {
        int contextStart = Math.max(0, termStart - 80);
        String prefix = normalizedGenerated.substring(contextStart, termStart);
        return NEGATED_FORBIDDEN_PREFIX_PATTERN.matcher(prefix).find();
    }

    private Pattern termPattern(String normalizedTerm) {
        return Pattern.compile("(^|[^\\p{Alnum}])("
                + Pattern.quote(normalizedTerm)
                + ")([^\\p{Alnum}]|$)");
    }

    private List<String> missingRequiredTerms(List<String> mustContain, String normalizedGenerated) {
        return mustContain.stream()
                .filter(term -> !containsTerm(normalize(term), normalizedGenerated))
                .toList();
    }

    private List<String> requiredTerms(String expectedAnswer, List<String> mustContain) {
        List<String> requiredTerms = new ArrayList<>();
        if (!isBlank(expectedAnswer)) {
            requiredTerms.add(expectedAnswer);
        }
        requiredTerms.addAll(safeList(mustContain));
        return List.copyOf(requiredTerms);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> !isBlank(value))
                .map(String::trim)
                .toList();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record Judgment(
            boolean correct,
            String failureReason,
            List<String> missingMustContain,
            List<String> matchedForbiddenTerms) {

        public Judgment {
            missingMustContain = missingMustContain == null ? List.of() : List.copyOf(missingMustContain);
            matchedForbiddenTerms = matchedForbiddenTerms == null ? List.of() : List.copyOf(matchedForbiddenTerms);
        }

        private static Judgment incorrect(
                String failureReason,
                List<String> missingMustContain,
                List<String> matchedForbiddenTerms) {
            return new Judgment(false, failureReason, missingMustContain, matchedForbiddenTerms);
        }
    }
}
