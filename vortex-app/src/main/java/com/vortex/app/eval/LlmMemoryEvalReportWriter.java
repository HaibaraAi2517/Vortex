package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.RecallDiagnostics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LlmMemoryEvalReportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final LlmMemoryEvalProperties properties;

    public WrittenReport write(LlmMemoryEvalReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Eval report must not be null");
        }

        Path outputDir = Path.of(properties.getReportOutputDir()).toAbsolutePath().normalize();
        String stamp = FILE_STAMP.format(report.getGeneratedAt());
        Path jsonPath = outputDir.resolve("llm-memory-eval-" + stamp + ".json");
        Path markdownPath = outputDir.resolve("llm-memory-eval-" + stamp + ".md");

        try {
            Files.createDirectories(outputDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), report);
            Files.writeString(
                    markdownPath,
                    toMarkdown(report),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return new WrittenReport(jsonPath, markdownPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write eval report to " + outputDir, e);
        }
    }

    private String toMarkdown(LlmMemoryEvalReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# LLM Memory Eval Report\n\n");
        builder.append("- GeneratedAt: ").append(report.getGeneratedAt()).append('\n');
        builder.append("- TotalCases: ").append(report.getTotalCases()).append('\n');
        builder.append("- TotalRuns: ").append(report.getTotalRuns()).append("\n\n");
        appendEnvironment(builder, report.getEnvironment());
        builder.append("## Mode Summary\n\n");
        builder.append("| Mode | Accuracy | Recall Hit Rate | Recovered Accuracy | L2 Recovery Hit Rate | Avg Latency (ms) | Feedback | Learning Sample Δ | Learning Update Δ | Correct | Total |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        safeMap(report.getModeSummaries()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    LlmMemoryEvalReport.ModeSummary summary = entry.getValue();
                    builder.append("| ")
                            .append(entry.getKey()).append(" | ")
                            .append(formatDecimal(summary.getAccuracy())).append(" | ")
                            .append(formatDecimal(summary.getRecallHitRate())).append(" | ")
                            .append(formatDecimal(summary.getRecoveredAccuracy())).append(" | ")
                            .append(formatDecimal(summary.getRecoveredL2HitRate())).append(" | ")
                            .append(formatDecimal(summary.getAverageLatencyMs())).append(" | ")
                            .append(summary.getFeedbackSubmitted()).append(" | ")
                            .append(summary.getLearningSampleCountDelta()).append(" | ")
                            .append(summary.getLearningUpdateCountDelta()).append(" | ")
                            .append(summary.getCorrect()).append(" | ")
                            .append(summary.getTotal()).append(" |\n");
                });
        builder.append("\n## Results\n\n");
        builder.append("| CaseId | Mode | Correct | Failure Reason | Runtime Type | Transient Runtime | Missing Must Contain | Matched Forbidden | Recall Hit | Recalled Tiers | Evicted | Feedback | Learning Update Δ | Prompt Tokens | Completion Tokens | Latency (ms) | Error |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | --- |\n");
        safeList(report.getResults()).forEach(result -> builder.append("| ")
                .append(result.getCaseId()).append(" | ")
                .append(result.getMode()).append(" | ")
                .append(result.isCorrect()).append(" | ")
                .append(formatNullable(result.getFailureReason())).append(" | ")
                .append(formatNullable(result.getRuntimeErrorType())).append(" | ")
                .append(formatNullable(result.getTransientRuntimeError())).append(" | ")
                .append(sanitizeMarkdown(String.join(",", safeList(result.getMissingMustContain())))).append(" | ")
                .append(sanitizeMarkdown(String.join(",", safeList(result.getMatchedForbiddenTerms())))).append(" | ")
                .append(result.isRecallHit()).append(" | ")
                .append(sanitizeMarkdown(String.join(",", safeList(result.getRecalledFromTiers())))).append(" | ")
                .append(formatNullable(result.getEvictedBeforeAnswer())).append(" | ")
                .append(formatNullable(result.getFeedbackSubmitted())).append(" | ")
                .append(formatDelta(result.getLearningActiveUpdateCountBefore(), result.getLearningActiveUpdateCountAfter())).append(" | ")
                .append(result.getPromptTokens() == null ? "" : result.getPromptTokens()).append(" | ")
                .append(result.getCompletionTokens() == null ? "" : result.getCompletionTokens()).append(" | ")
                .append(result.getLatencyMs()).append(" | ")
                .append(result.getErrorMessage() == null ? "" : sanitizeMarkdown(result.getErrorMessage()))
                .append(" |\n"));
        builder.append("\n## Latency Breakdown\n\n");
        builder.append("| CaseId | Mode | Store | Wait L2 | Force Evict | Recall | Prompt | Generation | Gen Req | Gen HTTP | Gen Parse | Gen Retry | Feedback | Wait Polls | Force Polls | Fillers |\n");
        builder.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        safeList(report.getResults()).forEach(result -> builder.append("| ")
                .append(result.getCaseId()).append(" | ")
                .append(result.getMode()).append(" | ")
                .append(result.getStoreLatencyMs()).append(" | ")
                .append(result.getRecoveryTargetWaitLatencyMs()).append(" | ")
                .append(result.getRecoveryForceLatencyMs()).append(" | ")
                .append(result.getRecallLatencyMs()).append(" | ")
                .append(result.getPromptAssemblyLatencyMs()).append(" | ")
                .append(result.getGenerationLatencyMs()).append(" | ")
                .append(result.getGenerationRequestBuildLatencyMs()).append(" | ")
                .append(result.getGenerationHttpRoundTripLatencyMs()).append(" | ")
                .append(result.getGenerationResponseParseLatencyMs()).append(" | ")
                .append(result.getGenerationRetryBackoffLatencyMs()).append(" | ")
                .append(result.getFeedbackLatencyMs()).append(" | ")
                .append(result.getRecoveryTargetWaitPollCount()).append(" | ")
                .append(result.getRecoveryForcePollCount()).append(" | ")
                .append(result.getRecoveryFillerFragmentsInserted()).append(" |\n"));
        builder.append("\n## Recall Diagnostics\n\n");
        builder.append("| CaseId | Mode | Empty Reason | Final Returned | Required Tags | L1 Cand | L1 Tag | L1 Sel | L1 Budget Reject | L2 Search Cand | L2 Search Accepted | L2 Search Dup Reject | L2 Search Tag Reject | L2 Search Budget Reject | L2 Fallback Cand | L2 Fallback Accepted | L2 Fallback Dup Reject | L2 Fallback Tag Reject | L2 Fallback Budget Reject | Find L1 | Find L3 | Find L2 | Find Miss | Enrich Fragment | Enrich Candidate | Enrich L2 Fallback | Enrich Reject |\n");
        builder.append("| --- | --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        safeList(report.getResults()).forEach(result -> {
            RecallDiagnostics diagnostics = result.getRecallDiagnostics();
            builder.append("| ")
                    .append(result.getCaseId()).append(" | ")
                    .append(result.getMode()).append(" | ")
                    .append(diagnostics == null ? "" : formatNullable(diagnostics.getEmptyRecallReason())).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getFinalReturnedCount()).append(" | ")
                    .append(diagnostics == null ? "" : sanitizeMarkdown(String.join(",", safeList(diagnostics.getRequiredTags())))).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL1CandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL1TagMatchedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL1SelectedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL1TokenBudgetRejectedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2SearchCandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2SearchAcceptedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2SearchDuplicateRejectedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2SearchTagRejectedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2SearchTokenBudgetRejectedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2NamespaceFallbackCandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2NamespaceFallbackAcceptedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2NamespaceFallbackDuplicateRejectedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2NamespaceFallbackTagRejectedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2NamespaceFallbackTokenBudgetRejectedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getFindFragmentL1HitCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getFindFragmentL3HitCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getFindFragmentL2HitCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getFindFragmentMissCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getEnrichFragmentTagMatchedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getEnrichCandidateTagMatchedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getEnrichL2TagFallbackMatchedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getEnrichTagRejectedCount()).append(" |\n");
        });
        builder.append("\n## Generation Telemetry\n\n");
        builder.append("| CaseId | Mode | HTTP Status | Attempts | Req Bytes | Resp Bytes | Gen Total ns | Serialize ns | Request Build ns | HTTP ns | Decode ns | JSON Parse ns | Parse ns |\n");
        builder.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        safeList(report.getResults()).forEach(result -> builder.append("| ")
                .append(result.getCaseId()).append(" | ")
                .append(result.getMode()).append(" | ")
                .append(formatNullable(result.getGenerationHttpStatusCode())).append(" | ")
                .append(formatNullable(result.getGenerationAttemptCount())).append(" | ")
                .append(formatNullable(result.getGenerationRequestBytes())).append(" | ")
                .append(formatNullable(result.getGenerationResponseBytes())).append(" | ")
                .append(result.getGenerationLatencyNanos()).append(" | ")
                .append(result.getGenerationRequestSerializationLatencyNanos()).append(" | ")
                .append(result.getGenerationRequestBuildLatencyNanos()).append(" | ")
                .append(result.getGenerationHttpRoundTripLatencyNanos()).append(" | ")
                .append(result.getGenerationResponseDecodeLatencyNanos()).append(" | ")
                .append(result.getGenerationResponseJsonParseLatencyNanos()).append(" | ")
                .append(result.getGenerationResponseParseLatencyNanos()).append(" |\n"));
        return builder.toString();
    }

    private void appendEnvironment(StringBuilder builder, LlmMemoryEvalEnvironmentSnapshot environment) {
        if (environment == null) {
            return;
        }
        builder.append("## Environment\n\n");
        builder.append("- CLI Main Class: ").append(nullToEmpty(environment.getCliMainClass())).append('\n');
        builder.append("- Generation Base URL: ").append(nullToEmpty(environment.getGenerationBaseUrl())).append('\n');
        builder.append("- Generation Model: ").append(nullToEmpty(environment.getGenerationModel())).append('\n');
        builder.append("- Generation Timeout (ms): ").append(formatNullable(environment.getGenerationTimeoutMs())).append('\n');
        builder.append("- BGE Model Path: ").append(nullToEmpty(environment.getBgeModelPath())).append('\n');
        builder.append("- BGE Safe Hash Mode: ").append(environment.isBgeSafeHashMode()).append('\n');
        builder.append("- L1 Max Tokens: ").append(formatNullable(environment.getL1MaxTokens())).append('\n');
        builder.append("- Milvus Collection: ").append(nullToEmpty(environment.getMilvusCollection())).append('\n');
        builder.append("- MinIO Key Prefix: ").append(nullToEmpty(environment.getMinioKeyPrefix())).append('\n');
        builder.append("- Dataset Location: ").append(nullToEmpty(environment.getDatasetLocation())).append('\n');
        builder.append("- Dataset Version: ").append(nullToEmpty(environment.getDatasetVersion())).append('\n');
        builder.append("- Baseline Profile Id: ").append(nullToEmpty(environment.getBaselineProfileId())).append('\n');
        builder.append("- Strict Verifier Profile Id: ").append(nullToEmpty(environment.getStrictVerifierProfileId())).append('\n');
        builder.append("- Eval System Prompt SHA-256: ").append(nullToEmpty(environment.getEvalSystemPromptSha256())).append('\n');
        builder.append("- Eval System Prompt Chars: ").append(formatNullable(environment.getEvalSystemPromptChars())).append('\n');
        builder.append("- Modes: ").append(sanitizeMarkdown(String.join(", ", safeList(environment.getModes())))).append('\n');
        builder.append("- Report Output Dir: ").append(nullToEmpty(environment.getReportOutputDir())).append('\n');
        builder.append("- Java Version: ").append(nullToEmpty(environment.getJavaVersion())).append('\n');
        builder.append("- OS: ").append(nullToEmpty(environment.getOsName()))
                .append(' ').append(nullToEmpty(environment.getOsVersion())).append('\n');
        builder.append("- User Dir: ").append(nullToEmpty(environment.getUserDir())).append("\n\n");
    }

    private String formatDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private String sanitizeMarkdown(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private String formatNullable(Boolean value) {
        return value == null ? "" : value.toString();
    }

    private String formatNullable(Integer value) {
        return value == null ? "" : value.toString();
    }

    private String formatNullable(Long value) {
        return value == null ? "" : value.toString();
    }

    private String formatNullable(String value) {
        return value == null ? "" : sanitizeMarkdown(value);
    }

    private String formatDelta(Long before, Long after) {
        if (before == null || after == null) {
            return "";
        }
        return Long.toString(after - before);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record WrittenReport(Path jsonPath, Path markdownPath) {
    }
}
