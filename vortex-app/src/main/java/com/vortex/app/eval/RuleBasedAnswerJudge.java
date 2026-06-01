package com.vortex.app.eval;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RuleBasedAnswerJudge {

    private static final Pattern INSUFFICIENT_ANSWER_PATTERN = Pattern.compile(
            "\\b(insufficient|unknown)\\b"
                    + "|\\b(?:cannot|can not|can't|cant|could not|couldn't)\\s+"
                    + "(?:determine|answer|tell|identify|infer|say)\\b"
                    + "|\\b(?:do not|don't|dont|did not|didn't)\\s+have\\b"
                    + "|\\bno\\s+(?:memory|fragments|information|details|context)\\b"
                    + "|\\bnone\\s+were\\s+(?:supplied|provided|given)\\b"
                    + "|\\bwithout\\s+additional\\s+(?:context|information|memory)\\b");

    public boolean isCorrect(String expectedAnswer, String generatedAnswer) {
        if (isBlank(expectedAnswer) || isBlank(generatedAnswer)) {
            return false;
        }
        String normalizedExpected = normalize(expectedAnswer);
        String normalizedGenerated = normalize(generatedAnswer);
        if (normalizedGenerated.equals(normalizedExpected)) {
            return true;
        }
        if (containsInsufficientCue(normalizedGenerated)) {
            return false;
        }
        return containsExpectedAnswerPhrase(normalizedExpected, normalizedGenerated);
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

    private String normalize(String value) {
        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
