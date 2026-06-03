package com.vortex.app.eval;

import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LlmMemoryEvalCliApplicationTest {

    @Test
    void shouldRemainUtilityStyleMainEntry() throws Exception {
        Constructor<LlmMemoryEvalCliApplication> constructor =
                LlmMemoryEvalCliApplication.class.getDeclaredConstructor();

        assertThat(Modifier.isFinal(LlmMemoryEvalCliApplication.class.getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
    }

    @Test
    void verifyCommandShouldAcceptExplicitOfficialV21Profile(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-v2-1.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), strictReport(
                "classpath:llm-memory-eval-set-v2-1.json",
                "v2.1",
                "official-v2.1-strict",
                "official-v2.1-strict"));

        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "official-v2.1-strict",
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

    @Test
    void verifyCommandShouldListProfiles(CapturedOutput output) {
        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--list-profiles"
        });

        assertThat(exitCode).isZero();
        assertThat(output.getOut())
                .contains("official-v2-strict [strict-report]")
                .contains("audit-v2-stability [audit-only]")
                .contains("official-v2.1-strict [strict-report]")
                .contains("contract-v2.1-candidate [strict-report]")
                .contains("official-v2.1-extended-strict [strict-report]")
                .contains("candidate-v2.1-extended [audit-only]")
                .contains("official-v3-real-agent-workload-strict [strict-report]")
                .contains("audit-v3-real-agent-workload [audit-only]");
    }

    @Test
    void verifyCommandShouldDescribeSelectedProfile(CapturedOutput output) {
        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "audit-v2-stability",
                "--describe"
        });

        assertThat(exitCode).isZero();
        assertThat(output.getOut())
                .contains("Profile: audit-v2-stability")
                .contains("Type: audit-only")
                .contains("Baseline ID: 20260601-mode-scoped-l2-wait-audit-5x-net")
                .contains("Dataset location: classpath:llm-memory-eval-set-v2.json")
                .contains("Strict verify expectations: none");
    }

    @Test
    void verifyCommandShouldDescribeOfficialV21Profile(CapturedOutput output) {
        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "official-v2.1-strict",
                "--describe"
        });

        assertThat(exitCode).isZero();
        assertThat(output.getOut())
                .contains("Profile: official-v2.1-strict")
                .contains("Type: strict-report")
                .contains("Baseline ID: 20260601-v2-009-contract-audit-5x-net")
                .contains("Dataset version: v2.1")
                .contains("Dataset location: classpath:llm-memory-eval-set-v2-1.json")
                .contains("Vortex-RecoveredMemory correct=15/15 accuracy=1.0 recoveredAccuracy=1.0 recoveredL2HitRate=1.0");
    }

    @Test
    void verifyCommandShouldDescribeV21ExtendedCandidateProfile(CapturedOutput output) {
        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "candidate-v2.1-extended",
                "--describe"
        });

        assertThat(exitCode).isZero();
        assertThat(output.getOut())
                .contains("Profile: candidate-v2.1-extended")
                .contains("Type: audit-only")
                .contains("Dataset version: v2.1-extended")
                .contains("Dataset location: classpath:llm-memory-eval-set-v2-1-extended.json")
                .contains("Strict verify expectations: none");
    }

    @Test
    void verifyCommandShouldDescribeV3RealAgentWorkloadProfile(CapturedOutput output) {
        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "official-v3-real-agent-workload-strict",
                "--describe"
        });

        assertThat(exitCode).isZero();
        assertThat(output.getOut())
                .contains("Profile: official-v3-real-agent-workload-strict")
                .contains("Type: strict-report")
                .contains("Baseline ID: 20260603-v3-real-agent-workload-audit-002")
                .contains("Dataset version: v3-real-agent-workload")
                .contains("Dataset location: classpath:llm-memory-eval-set-v3-real-agent-workload.json")
                .contains("Vortex-RecoveredMemory correct=12/12 accuracy=1.0 recoveredAccuracy=1.0 recoveredL2HitRate=1.0");
    }

    @Test
    void verifyCommandShouldDescribeV3AuditProfileAsHistoricalAuditOnly(CapturedOutput output) {
        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "audit-v3-real-agent-workload",
                "--describe"
        });

        assertThat(exitCode).isZero();
        assertThat(output.getOut())
                .contains("Profile: audit-v3-real-agent-workload")
                .contains("Type: audit-only")
                .contains("Baseline ID: candidate-v3-real-agent-workload")
                .contains("Strict verify expectations: none");
    }

    @Test
    void verifyCommandShouldRejectV3AuditProfileForSingleReport(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-v3-agent.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), strictReport(
                "classpath:llm-memory-eval-set-v3-real-agent-workload.json",
                "v3-real-agent-workload",
                "audit-v3-real-agent-workload",
                "",
                12));

        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "audit-v3-real-agent-workload",
                reportPath.toString()
        });

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void verifyCommandShouldAcceptExplicitOfficialV3RealAgentWorkloadProfile(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-v3-agent.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), strictReport(
                "classpath:llm-memory-eval-set-v3-real-agent-workload.json",
                "v3-real-agent-workload",
                "official-v3-real-agent-workload-strict",
                "official-v3-real-agent-workload-strict",
                12));

        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "official-v3-real-agent-workload-strict",
                reportPath.toString()
        });

        assertThat(exitCode).isZero();
    }

    @Test
    void verifyCommandShouldAcceptExplicitOfficialV21ExtendedProfile(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Path reportPath = tempDir.resolve("llm-memory-eval-v2-1-extended.json");
        JsonMapperFactory.create().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), strictReport(
                "classpath:llm-memory-eval-set-v2-1-extended.json",
                "v2.1-extended",
                "official-v2.1-extended-strict",
                "official-v2.1-extended-strict",
                30));

        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "official-v2.1-extended-strict",
                reportPath.toString()
        });

        assertThat(exitCode).isZero();
    }

    @Test
    void verifyCommandShouldDescribeOfficialV21ExtendedProfile(CapturedOutput output) {
        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "verify",
                "--profile",
                "official-v2.1-extended-strict",
                "--describe"
        });

        assertThat(exitCode).isZero();
        assertThat(output.getOut())
                .contains("Profile: official-v2.1-extended-strict")
                .contains("Type: strict-report")
                .contains("Baseline ID: 20260602-v2-1-extended-candidate-audit-generation-retry-001")
                .contains("Dataset version: v2.1-extended")
                .contains("Dataset location: classpath:llm-memory-eval-set-v2-1-extended.json")
                .contains("Vortex-RecoveredMemory correct=30/30 accuracy=1.0 recoveredAccuracy=1.0 recoveredL2HitRate=1.0");
    }

    private LlmMemoryEvalReport strictReport(
            String datasetLocation,
            String datasetVersion,
            String baselineProfileId,
            String strictVerifierProfileId) {
        return strictReport(datasetLocation, datasetVersion, baselineProfileId, strictVerifierProfileId, 15);
    }

    private LlmMemoryEvalReport strictReport(
            String datasetLocation,
            String datasetVersion,
            String baselineProfileId,
            String strictVerifierProfileId,
            int totalCases) {
        return LlmMemoryEvalReport.builder()
                .generatedAt(Instant.parse("2026-06-01T12:00:00Z"))
                .totalCases(totalCases)
                .totalRuns(totalCases * 3)
                .modeSummaries(Map.of(
                        "Baseline-NoMemory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(totalCases)
                                .correct(0)
                                .accuracy(0.0d)
                                .build(),
                        "Vortex-Memory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(totalCases)
                                .correct(totalCases)
                                .accuracy(1.0d)
                                .build(),
                        "Vortex-RecoveredMemory", LlmMemoryEvalReport.ModeSummary.builder()
                                .total(totalCases)
                                .correct(totalCases)
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
