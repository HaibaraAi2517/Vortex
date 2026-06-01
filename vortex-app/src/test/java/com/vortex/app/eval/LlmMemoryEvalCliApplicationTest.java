package com.vortex.app.eval;

import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMemoryEvalCliApplicationTest {

    @Test
    void shouldRemainUtilityStyleMainEntry() throws Exception {
        Constructor<LlmMemoryEvalCliApplication> constructor =
                LlmMemoryEvalCliApplication.class.getDeclaredConstructor();

        assertThat(Modifier.isFinal(LlmMemoryEvalCliApplication.class.getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
    }

    @Test
    void verifyCommandShouldAcceptExplicitContractProfile(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-v2-1.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), strictReport(
                "classpath:llm-memory-eval-set-v2-1.json",
                "v2.1",
                "contract-v2.1-candidate",
                "contract-v2.1-candidate"));

        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "contract-v2.1-candidate",
                reportPath.toString()
        });

        assertThat(exitCode).isZero();
    }

    @Test
    void verifyCommandShouldRejectAuditOnlyProfile(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-v2.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), strictReport(
                "classpath:llm-memory-eval-set-v2.json",
                "v2",
                "audit-v2-stability",
                "official-v2-strict"));

        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "audit-v2-stability",
                reportPath.toString()
        });

        assertThat(exitCode).isEqualTo(1);
    }

    private LlmMemoryEvalReport strictReport(
            String datasetLocation,
            String datasetVersion,
            String baselineProfileId,
            String strictVerifierProfileId) {
        return LlmMemoryEvalReport.builder()
                .generatedAt(Instant.parse("2026-06-01T12:00:00Z"))
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
                        .datasetLocation(datasetLocation)
                        .datasetVersion(datasetVersion)
                        .baselineProfileId(baselineProfileId)
                        .strictVerifierProfileId(strictVerifierProfileId)
                        .evalSystemPromptSha256("e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3")
                        .modes(List.of("Baseline-NoMemory", "Vortex-Memory", "Vortex-RecoveredMemory"))
                        .build())
                .build();
    }
}
