package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class LlmMemoryEvalBaselineVerifier {

    static final String OFFICIAL_BASELINE_ID = "20260529-real-bge-v2-006";
    static final String EXPECTED_DATASET_LOCATION = "classpath:llm-memory-eval-set-v2.json";
    static final String EXPECTED_GENERATION_BASE_URL = "https://sub2.congmingai.com/v1";
    static final String EXPECTED_GENERATION_MODEL = "gpt-5.2";
    static final long EXPECTED_L1_MAX_TOKENS = 96L;
    static final String EXPECTED_SYSTEM_PROMPT_SHA256 =
            "e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3";
    static final List<String> EXPECTED_MODES = List.of(
            "Baseline-NoMemory",
            "Vortex-Memory",
            "Vortex-RecoveredMemory");

    private final ObjectMapper objectMapper;

    public LlmMemoryEvalBaselineVerifier(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public LlmMemoryEvalBaselineVerificationResult verify(Path reportPath) {
        Path normalizedPath = Objects.requireNonNull(reportPath, "reportPath must not be null")
                .toAbsolutePath()
                .normalize();
        return verify(normalizedPath.toString(), readReport(normalizedPath));
    }

    public LlmMemoryEvalBaselineVerificationResult verify(String reportPath, LlmMemoryEvalReport report) {
        List<LlmMemoryEvalBaselineVerificationResult.Drift> drifts = new ArrayList<>();
        LlmMemoryEvalEnvironmentSnapshot environment = report == null ? null : report.getEnvironment();

        expectEqual(drifts, "environment.datasetLocation",
                EXPECTED_DATASET_LOCATION, environment == null ? null : environment.getDatasetLocation());
        expectEqual(drifts, "environment.generationBaseUrl",
                EXPECTED_GENERATION_BASE_URL, environment == null ? null : environment.getGenerationBaseUrl());
        expectEqual(drifts, "environment.generationModel",
                EXPECTED_GENERATION_MODEL, environment == null ? null : environment.getGenerationModel());
        expectEqual(drifts, "environment.l1MaxTokens",
                EXPECTED_L1_MAX_TOKENS, environment == null ? null : environment.getL1MaxTokens());
        expectEqual(drifts, "environment.evalSystemPromptSha256",
                EXPECTED_SYSTEM_PROMPT_SHA256, environment == null ? null : environment.getEvalSystemPromptSha256());
        expectExactModes(drifts, environment == null ? null : environment.getModes());

        Map<String, LlmMemoryEvalReport.ModeSummary> modeSummaries =
                report == null || report.getModeSummaries() == null ? Map.of() : report.getModeSummaries();
        verifyModeSummary(drifts, modeSummaries, "Baseline-NoMemory", 0, 15, null, null, null);
        verifyModeSummary(drifts, modeSummaries, "Vortex-Memory", 15, 15, 1.0d, null, null);
        verifyModeSummary(drifts, modeSummaries, "Vortex-RecoveredMemory", 15, 15, 1.0d, 1.0d, 1.0d);

        return LlmMemoryEvalBaselineVerificationResult.builder()
                .baselineId(OFFICIAL_BASELINE_ID)
                .reportPath(reportPath)
                .passed(drifts.isEmpty())
                .drifts(List.copyOf(drifts))
                .build();
    }

    private LlmMemoryEvalReport readReport(Path reportPath) {
        try {
            return objectMapper.readValue(reportPath.toFile(), LlmMemoryEvalReport.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read eval report json: " + reportPath, e);
        }
    }

    private void expectExactModes(List<LlmMemoryEvalBaselineVerificationResult.Drift> drifts, List<String> actualModes) {
        Set<String> expectedModes = new LinkedHashSet<>(EXPECTED_MODES);
        Set<String> actualModeSet = actualModes == null ? null : new LinkedHashSet<>(actualModes);
        boolean matches = actualModes != null
                && actualModes.size() == EXPECTED_MODES.size()
                && expectedModes.equals(actualModeSet);
        if (!matches) {
            drifts.add(new LlmMemoryEvalBaselineVerificationResult.Drift(
                    "environment.modes",
                    formatValue(EXPECTED_MODES),
                    formatValue(actualModes)));
        }
    }

    private void verifyModeSummary(
            List<LlmMemoryEvalBaselineVerificationResult.Drift> drifts,
            Map<String, LlmMemoryEvalReport.ModeSummary> modeSummaries,
            String modeName,
            int expectedCorrect,
            int expectedTotal,
            Double expectedAccuracy,
            Double expectedRecoveredAccuracy,
            Double expectedRecoveredL2HitRate) {
        LlmMemoryEvalReport.ModeSummary summary = modeSummaries.get(modeName);
        if (summary == null) {
            drifts.add(new LlmMemoryEvalBaselineVerificationResult.Drift(
                    "modeSummaries." + modeName,
                    "<present>",
                    "<missing>"));
            return;
        }

        expectEqual(drifts, "modeSummaries." + modeName + ".correct", expectedCorrect, summary.getCorrect());
        expectEqual(drifts, "modeSummaries." + modeName + ".total", expectedTotal, summary.getTotal());
        if (expectedAccuracy != null) {
            expectEqual(drifts, "modeSummaries." + modeName + ".accuracy", expectedAccuracy, summary.getAccuracy());
        }
        if (expectedRecoveredAccuracy != null) {
            expectEqual(
                    drifts,
                    "modeSummaries." + modeName + ".recoveredAccuracy",
                    expectedRecoveredAccuracy,
                    summary.getRecoveredAccuracy());
        }
        if (expectedRecoveredL2HitRate != null) {
            expectEqual(
                    drifts,
                    "modeSummaries." + modeName + ".recoveredL2HitRate",
                    expectedRecoveredL2HitRate,
                    summary.getRecoveredL2HitRate());
        }
    }

    private void expectEqual(
            List<LlmMemoryEvalBaselineVerificationResult.Drift> drifts,
            String field,
            Object expected,
            Object actual) {
        if (!Objects.equals(expected, actual)) {
            drifts.add(new LlmMemoryEvalBaselineVerificationResult.Drift(
                    field,
                    formatValue(expected),
                    formatValue(actual)));
        }
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return "\"" + stringValue + "\"";
        }
        return value.toString();
    }
}
