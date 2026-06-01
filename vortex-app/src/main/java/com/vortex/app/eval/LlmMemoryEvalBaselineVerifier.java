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
        return verify(reportPath, LlmMemoryEvalBaselineProfile.OFFICIAL_V2_STRICT);
    }

    public LlmMemoryEvalBaselineVerificationResult verify(Path reportPath, LlmMemoryEvalBaselineProfile profile) {
        Path normalizedPath = Objects.requireNonNull(reportPath, "reportPath must not be null")
                .toAbsolutePath()
                .normalize();
        return verify(normalizedPath.toString(), readReport(normalizedPath), profile);
    }

    public LlmMemoryEvalBaselineVerificationResult verify(String reportPath, LlmMemoryEvalReport report) {
        return verify(reportPath, report, LlmMemoryEvalBaselineProfile.OFFICIAL_V2_STRICT);
    }

    public LlmMemoryEvalBaselineVerificationResult verify(
            String reportPath,
            LlmMemoryEvalReport report,
            LlmMemoryEvalBaselineProfile profile) {
        LlmMemoryEvalBaselineProfile baselineProfile =
                Objects.requireNonNull(profile, "profile must not be null");
        if (!baselineProfile.strictReportProfile()) {
            throw new IllegalArgumentException(
                    "Baseline profile '" + baselineProfile.id() + "' does not support strict single-report verify");
        }

        List<LlmMemoryEvalBaselineVerificationResult.Drift> drifts = new ArrayList<>();
        LlmMemoryEvalEnvironmentSnapshot environment = report == null ? null : report.getEnvironment();

        expectEqual(drifts, "environment.datasetLocation",
                baselineProfile.datasetLocation(), environment == null ? null : environment.getDatasetLocation());
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
        for (LlmMemoryEvalBaselineProfile.ModeExpectation expectation : baselineProfile.modeExpectations()) {
            verifyModeSummary(drifts, modeSummaries, expectation);
        }

        return LlmMemoryEvalBaselineVerificationResult.builder()
                .baselineId(baselineProfile.baselineId())
                .baselineProfileId(baselineProfile.id())
                .datasetVersion(baselineProfile.datasetVersion())
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
            LlmMemoryEvalBaselineProfile.ModeExpectation expectation) {
        String modeName = expectation.modeName();
        LlmMemoryEvalReport.ModeSummary summary = modeSummaries.get(modeName);
        if (summary == null) {
            drifts.add(new LlmMemoryEvalBaselineVerificationResult.Drift(
                    "modeSummaries." + modeName,
                    "<present>",
                    "<missing>"));
            return;
        }

        expectEqual(drifts, "modeSummaries." + modeName + ".correct", expectation.expectedCorrect(), summary.getCorrect());
        expectEqual(drifts, "modeSummaries." + modeName + ".total", expectation.expectedTotal(), summary.getTotal());
        if (expectation.expectedAccuracy() != null) {
            expectEqual(
                    drifts,
                    "modeSummaries." + modeName + ".accuracy",
                    expectation.expectedAccuracy(),
                    summary.getAccuracy());
        }
        if (expectation.expectedRecoveredAccuracy() != null) {
            expectEqual(
                    drifts,
                    "modeSummaries." + modeName + ".recoveredAccuracy",
                    expectation.expectedRecoveredAccuracy(),
                    summary.getRecoveredAccuracy());
        }
        if (expectation.expectedRecoveredL2HitRate() != null) {
            expectEqual(
                    drifts,
                    "modeSummaries." + modeName + ".recoveredL2HitRate",
                    expectation.expectedRecoveredL2HitRate(),
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
