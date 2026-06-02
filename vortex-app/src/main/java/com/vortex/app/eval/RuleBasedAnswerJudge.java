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
    private static final Pattern OBSOLETE_FORBIDDEN_PREFIX_PATTERN = Pattern.compile(
            "(?:^|[^\\p{Alnum}])(?:"
                    + "(?:previously|formerly|historically)\\s+"
                    + "|(?:was|were|is|are|had\\s+been)\\s+(?:previously|formerly|historically)\\s+"
                    + "|(?:is|are|was|were)\\s+no\\s+longer\\s+"
                    + "|no\\s+longer\\s+"
                    + "|used\\s+to\\s+be\\s+"
                    + ")(?:[\\p{Alnum}'-]+\\s+){0,8}$");
    private static final Pattern OBSOLETE_FORBIDDEN_SUFFIX_PATTERN = Pattern.compile(
            "^\\s*(?:(?:was|were|is|are)\\s+)?(?:only\\s+)?(?:a\\s+|an\\s+|the\\s+)?"
                    + "(?:previous|prior|old|former|outdated|deprecated|obsolete)\\b"
                    + "|^\\s*(?:was|were|is|are)\\s+no\\s+longer\\b"
                    + "|^\\s*(?:was|were)\\s+(?:previously|formerly)\\b"
                    + "|^\\s+(?:tier\\s+)?(?:reference\\s+|note\\s+)?(?:is|was)?\\s*(?:explicitly\\s+)?"
                    + "(?:a\\s+|an\\s+|the\\s+)?(?:previous|prior|old|former|outdated|deprecated|obsolete|historical|pre-[a-z0-9-]+)\\b"
                    + "|^\\s+(?:tier\\s+)?(?:is|was)?\\s*(?:explicitly\\s+)?described\\s+as\\s+"
                    + "(?:a\\s+|an\\s+|the\\s+)?(?:previous|prior|old|former|outdated|deprecated|obsolete|historical|pre-[a-z0-9-]+)\\b"
                    + "|^\\s+(?:tier\\s+)?(?:reference\\s+|note\\s+)?(?:is|was)?\\s*(?:explicitly\\s+)?from\\s+before\\b"
                    + "|^\\s+(?:tier\\s+)?was\\s+only\\s+noted\\s+as\\b"
                    + "|^\\s+was\\s+(?:explicitly\\s+)?from\\s+(?:last\\s+week|before|the\\s+previous|the\\s+prior|the\\s+old)\\b"
                    + "|^\\s*(?:alerts?\\s+)?[,;:]?\\s*but\\s+(?:the\\s+)?current\\s+"
                    + "(?:preference|state|status|setting|value)\\b");

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
                || containsTerm(normalizedExpected, normalizedGenerated);
        if (!missingMustContain.isEmpty() || !expectedAnswerPresent) {
            return Judgment.incorrect(FAILURE_ANSWER_MISSING_FACT, missingMustContain, List.of());
        }

        return new Judgment(true, null, List.of(), List.of());
    }

    private boolean containsInsufficientCue(String normalizedGenerated) {
        return INSUFFICIENT_ANSWER_PATTERN.matcher(normalizedGenerated).find();
    }

    private boolean containsTerm(String normalizedTerm, String normalizedGenerated) {
        if (isBlank(normalizedTerm)) {
            return false;
        }
        return termPattern(normalizedTerm).matcher(normalizedGenerated).find()
                || containsEquivalentRangeTerm(normalizedTerm, normalizedGenerated)
                || containsEquivalentBulletPointTerm(normalizedTerm, normalizedGenerated);
    }

    private boolean containsForbiddenTerm(String normalizedTerm, String normalizedGenerated) {
        if (isBlank(normalizedTerm)) {
            return false;
        }
        Matcher matcher = termPattern(normalizedTerm).matcher(normalizedGenerated);
        while (matcher.find()) {
            if (!hasSafeForbiddenContext(normalizedGenerated, matcher.start(2), matcher.end(2))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSafeForbiddenContext(String normalizedGenerated, int termStart, int termEnd) {
        return hasNegatedForbiddenContext(normalizedGenerated, termStart)
                || hasObsoleteForbiddenPrefixContext(normalizedGenerated, termStart)
                || hasObsoleteForbiddenContext(normalizedGenerated, termEnd);
    }

    private boolean hasNegatedForbiddenContext(String normalizedGenerated, int termStart) {
        int contextStart = Math.max(0, termStart - 80);
        String prefix = normalizedGenerated.substring(contextStart, termStart);
        return NEGATED_FORBIDDEN_PREFIX_PATTERN.matcher(prefix).find();
    }

    private boolean hasObsoleteForbiddenPrefixContext(String normalizedGenerated, int termStart) {
        int contextStart = Math.max(0, termStart - 120);
        String prefix = normalizedGenerated.substring(contextStart, termStart);
        return OBSOLETE_FORBIDDEN_PREFIX_PATTERN.matcher(prefix).find();
    }

    private boolean hasObsoleteForbiddenContext(String normalizedGenerated, int termEnd) {
        int contextEnd = Math.min(normalizedGenerated.length(), termEnd + 80);
        String suffix = normalizedGenerated.substring(termEnd, contextEnd);
        return OBSOLETE_FORBIDDEN_SUFFIX_PATTERN.matcher(suffix).find();
    }

    private Pattern termPattern(String normalizedTerm) {
        return Pattern.compile("(^|[^\\p{Alnum}])("
                + Pattern.quote(normalizedTerm)
                + ")([^\\p{Alnum}]|$)");
    }

    private boolean containsEquivalentRangeTerm(String normalizedTerm, String normalizedGenerated) {
        String canonicalTerm = canonicalizeRangeConnectors(normalizedTerm);
        String canonicalGenerated = canonicalizeRangeConnectors(normalizedGenerated);
        if (canonicalTerm.equals(normalizedTerm) && canonicalGenerated.equals(normalizedGenerated)) {
            return false;
        }
        return termPattern(canonicalTerm).matcher(canonicalGenerated).find();
    }

    private String canonicalizeRangeConnectors(String normalizedValue) {
        return normalizedValue.replaceAll(
                "\\b((?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+)from\\s+"
                        + "(\\d{1,2}:\\d{2}(?:\\s+utc)?)\\s+(?:to|through|until|-)\\s+(\\d{1,2}:\\d{2}\\s+utc)\\b",
                "$1$2 to $3").replaceAll(
                "\\b(from\\s+)?(\\d{1,2}:\\d{2}(?:\\s+utc)?)\\s+(?:to|through|until|-)\\s+(\\d{1,2}:\\d{2}\\s+utc)\\b",
                "$2 to $3");
    }

    private boolean containsEquivalentBulletPointTerm(String normalizedTerm, String normalizedGenerated) {
        if (!"bullet points".equals(normalizedTerm)) {
            return false;
        }
        String canonicalGenerated = normalizedGenerated
                .replace("bullet-point", "bullet point")
                .replace("bullet points", "bullet point");
        return termPattern("bullet point").matcher(canonicalGenerated).find();
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
                .replaceAll("[\\u2018\\u2019\\u201B\\u2032]", "'")
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
