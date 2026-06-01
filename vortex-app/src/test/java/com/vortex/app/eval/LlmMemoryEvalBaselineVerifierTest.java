package com.vortex.app.eval;

import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMemoryEvalBaselineVerifierTest {

    @Test
    void verifyShouldPassForOfficialBaselineReport(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-baseline.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), officialBaselineReport());

        LlmMemoryEvalBaselineVerifier verifier = new LlmMemoryEvalBaselineVerifier(JsonMapperFactory.create());
        LlmMemoryEvalBaselineVerificationResult result = verifier.verify(reportPath);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getBaselineProfileId()).isEqualTo("official-v2-strict");
        assertThat(result.getDatasetVersion()).isEqualTo("v2");
        assertThat(result.getDrifts()).isEmpty();
        assertThat(result.renderHumanReadable()).contains("still matches LLM memory eval baseline profile");
    }

    @Test
    void verifyShouldDescribeDriftWhenReportDeviates(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-drifted.json");
        LlmMemoryEvalReport driftedReport = officialBaselineReport();
        driftedReport.getEnvironment().setGenerationModel("gpt-4.1");
        driftedReport.getModeSummaries().get("Vortex-Memory").setCorrect(14);
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), driftedReport);

        LlmMemoryEvalBaselineVerifier verifier = new LlmMemoryEvalBaselineVerifier(JsonMapperFactory.create());
        LlmMemoryEvalBaselineVerificationResult result = verifier.verify(reportPath);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getDrifts())
                .extracting(LlmMemoryEvalBaselineVerificationResult.Drift::field)
                .contains("environment.generationModel", "modeSummaries.Vortex-Memory.correct");
        assertThat(result.renderHumanReadable()).contains("expected=\"gpt-5.2\" actual=\"gpt-4.1\"");
        assertThat(result.renderHumanReadable()).contains("modeSummaries.Vortex-Memory.correct expected=15 actual=14");
    }

    @Test
    void verifyShouldPassForContractV21Profile(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-v2-1.json");
        LlmMemoryEvalReport report = officialBaselineReport();
        report.getEnvironment().setDatasetLocation("classpath:llm-memory-eval-set-v2-1.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        LlmMemoryEvalBaselineVerifier verifier = new LlmMemoryEvalBaselineVerifier(JsonMapperFactory.create());
        LlmMemoryEvalBaselineVerificationResult result =
                verifier.verify(reportPath, LlmMemoryEvalBaselineProfile.CONTRACT_V2_1_CANDIDATE);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getBaselineProfileId()).isEqualTo("contract-v2.1-candidate");
        assertThat(result.getDatasetVersion()).isEqualTo("v2.1");
        assertThat(result.getDrifts()).isEmpty();
    }

    @Test
    void defaultOfficialProfileShouldRejectV21Dataset(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-v2-1.json");
        LlmMemoryEvalReport report = officialBaselineReport();
        report.getEnvironment().setDatasetLocation("classpath:llm-memory-eval-set-v2-1.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        LlmMemoryEvalBaselineVerifier verifier = new LlmMemoryEvalBaselineVerifier(JsonMapperFactory.create());
        LlmMemoryEvalBaselineVerificationResult result = verifier.verify(reportPath);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getBaselineProfileId()).isEqualTo("official-v2-strict");
        assertThat(result.getDrifts())
                .extracting(LlmMemoryEvalBaselineVerificationResult.Drift::field)
                .contains("environment.datasetLocation");
    }

    private LlmMemoryEvalReport officialBaselineReport() {
        return LlmMemoryEvalReport.builder()
                .generatedAt(Instant.parse("2026-05-29T14:00:02.368131900Z"))
                .totalCases(15)
                .totalRuns(45)
                .modeSummaries(Map.of(
                        "Baseline-NoMemory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(15)
                                .correct(0)
                                .accuracy(0.0d)
                                .build(),
                        "Vortex-Memory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(15)
                                .correct(15)
                                .accuracy(1.0d)
                                .build(),
                        "Vortex-RecoveredMemory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(15)
                                .correct(15)
                                .accuracy(1.0d)
                                .recoveredAccuracy(1.0d)
                                .recoveredL2HitRate(1.0d)
                                .build()))
                .environment(LlmMemoryEvalEnvironmentSnapshot.builder()
                        .generationBaseUrl("https://sub2.congmingai.com/v1")
                        .generationModel("gpt-5.2")
                        .l1MaxTokens(96L)
                        .datasetLocation("classpath:llm-memory-eval-set-v2.json")
                        .datasetVersion("v2")
                        .baselineProfileId("audit-v2-stability")
                        .strictVerifierProfileId("official-v2-strict")
                        .evalSystemPromptSha256("e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3")
                        .modes(List.of("Baseline-NoMemory", "Vortex-Memory", "Vortex-RecoveredMemory"))
                        .build())
                .build();
    }
}
