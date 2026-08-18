package com.vortex.kernel.hmc;

import java.util.regex.Pattern;

/** Detects queries where a lexical branch is likely to add exact-match value. */
final class ExactQueryFeatureDetector {

    static final double STRONG_KEYWORD_WEIGHT = 0.15d;
    static final double STRUCTURED_NUMBER_WEIGHT = 0.08d;

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}\\b");
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)(?:\\b[a-z]:\\\\\\S+|\\\\\\\\[^\\s\\\\]+\\\\\\S+)");
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?:^|\\s)(?:\\.{0,2}/|/)[^\\s]+");
    private static final Pattern FILE_NAME = Pattern.compile(
            "(?i)\\b[\\w-]+\\.(?:json|ya?ml|xml|toml|ini|conf|properties|java|kt|py|js|ts|tsx|jsx|md|txt|csv|sql|sh|ps1|bat|cmd|log)\\b");
    private static final Pattern COMMAND = Pattern.compile(
            "`[^`]+`|--[a-zA-Z0-9-]+|(?:^|\\s)[$>]\\s+\\S+");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "\\b(?:[A-Z]{2,}[A-Z0-9]*[-_:]\\d+[A-Z0-9_-]*|[A-Za-z][A-Za-z0-9]*[_:][A-Za-z0-9][A-Za-z0-9_:-]*)\\b");
    private static final Pattern STRUCTURED_NUMBER = Pattern.compile(
            "(?i)(?:\\bv(?:ersion)?\\s*\\d+(?:\\.\\d+){1,3}\\b|(?<!\\d)\\d{1,4}[/-]\\d{1,2}(?:[/-]\\d{1,4})?(?!\\d))");

    boolean shouldUseKeyword(String query) {
        return analyze(query).enabled();
    }

    KeywordSignal analyze(String query) {
        if (query == null || query.isBlank()) {
            return KeywordSignal.disabled();
        }
        if (EMAIL.matcher(query).find()) {
            return new KeywordSignal(true, STRONG_KEYWORD_WEIGHT, "EMAIL");
        }
        if (COMMAND.matcher(query).find()) {
            return new KeywordSignal(true, STRONG_KEYWORD_WEIGHT, "COMMAND");
        }
        if (WINDOWS_PATH.matcher(query).find()
                || UNIX_PATH.matcher(query).find()
                || FILE_NAME.matcher(query).find()) {
            return new KeywordSignal(true, STRONG_KEYWORD_WEIGHT, "PATH_OR_FILE");
        }
        if (UUID.matcher(query).find() || IDENTIFIER.matcher(query).find()) {
            return new KeywordSignal(true, STRONG_KEYWORD_WEIGHT, "IDENTIFIER");
        }
        if (STRUCTURED_NUMBER.matcher(query).find()) {
            return new KeywordSignal(true, STRUCTURED_NUMBER_WEIGHT, "STRUCTURED_NUMBER");
        }
        return KeywordSignal.disabled();
    }

    record KeywordSignal(boolean enabled, double fusionWeight, String reason) {
        static KeywordSignal disabled() {
            return new KeywordSignal(false, 0.0d, "NONE");
        }
    }
}
