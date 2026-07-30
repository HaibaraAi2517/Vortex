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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecallBenchmarkReportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final LlmMemoryEvalProperties properties;

    public WrittenReport write(RecallBenchmarkReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Recall benchmark report must not be null");
        }
        Path outputDir = Path.of(properties.getReportOutputDir()).toAbsolutePath().normalize();
        String stamp = FILE_STAMP.format(report.getGeneratedAt());
        Path jsonPath = outputDir.resolve("recall-benchmark-" + stamp + ".json");
        Path markdownPath = outputDir.resolve("recall-benchmark-" + stamp + ".md");
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
            throw new IllegalStateException("Failed to write recall benchmark report to " + outputDir, e);
        }
    }

    private String toMarkdown(RecallBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Recall Benchmark Report\n\n");
        builder.append("- GeneratedAt: ").append(report.getGeneratedAt()).append('\n');
        builder.append("- RunId: ").append(nullToEmpty(report.getRunId())).append('\n');
        builder.append("- DatasetLocation: ").append(nullToEmpty(report.getDatasetLocation())).append('\n');
        builder.append("- TotalCases: ").append(report.getTotalCases()).append('\n');
        builder.append("- TotalRuns: ").append(report.getTotalRuns()).append('\n');
        builder.append("- TopK: ").append(report.getTopK()).append('\n');
        builder.append("- EvaluationKs: ").append(sanitize(safeList(report.getEvaluationKs()).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", ")))).append('\n');
        builder.append("- TokenBudget: ").append(report.getTokenBudget()).append('\n');
        builder.append("- Modes: ").append(sanitize(String.join(", ", safeList(report.getModes())))).append("\n\n");
        appendEnvironmentSnapshot(builder, report.getEnvironmentSnapshot());
        appendModeSummary(builder, report.getModeSummaries());
        appendResults(builder, report.getResults());
        appendDiagnostics(builder, report.getResults());
        return builder.toString();
    }

    private void appendEnvironmentSnapshot(
            StringBuilder builder,
            LlmMemoryEvalEnvironmentSnapshot environment) {
        if (environment == null) {
            return;
        }
        builder.append("## Environment Snapshot\n\n");
        builder.append("- Hardware: ").append(sanitize(environment.getHardwareDescription())).append('\n');
        builder.append("- GPU: ").append(sanitize(environment.getGpuDescription())).append('\n');
        builder.append("- Java: ").append(sanitize(environment.getJavaVersion())).append('\n');
        builder.append("- OS: ").append(sanitize(environment.getOsName()))
                .append(' ').append(sanitize(environment.getOsVersion()))
                .append(" (").append(sanitize(environment.getOsArchitecture())).append(")\n");
        builder.append("- AvailableProcessors: ").append(environment.getAvailableProcessors()).append('\n');
        builder.append("- MaxHeapBytes: ").append(environment.getMaxHeapBytes()).append('\n');
        builder.append("- RecallTopK: ").append(environment.getRecallTopK()).append('\n');
        builder.append("- RecallAblationModes: ")
                .append(sanitize(String.join(", ", safeList(environment.getRecallAblationModes()))))
                .append('\n');
        builder.append("- CrossEncoderCandidatePoolLimit: ")
                .append(environment.getCrossEncoderCandidatePoolLimit()).append("\n\n");
    }

    private void appendModeSummary(
            StringBuilder builder,
            Map<String, RecallBenchmarkReport.ModeSummary> summaries) {
        builder.append("## Mode Summary\n\n");
        builder.append("| Mode | Recall@K | Recall@K Lift vs Vector+Rerank | Recall@K Rel Lift vs Vector+Rerank | Case Hit Rate | All Expected Rate | Precision@K | MRR | NDCG | Avg Latency (ms) | P50 (ms) | P95 (ms) | P99 (ms) | Errors | Total |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        safeMap(summaries).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    RecallBenchmarkReport.ModeSummary summary = entry.getValue();
                    builder.append("| ")
                            .append(entry.getKey()).append(" | ")
                            .append(format(summary.getRecallAtK())).append(" | ")
                            .append(format(summary.getRecallAtKLiftVsVectorOnly())).append(" | ")
                            .append(format(summary.getRecallAtKRelativeLiftVsVectorOnly())).append(" | ")
                            .append(format(summary.getCaseHitRate())).append(" | ")
                            .append(format(summary.getAllExpectedHitRate())).append(" | ")
                            .append(format(summary.getPrecisionAtK())).append(" | ")
                            .append(format(summary.getMrr())).append(" | ")
                            .append(format(summary.getNdcg())).append(" | ")
                            .append(format(summary.getAverageLatencyMs())).append(" | ")
                            .append(format(summary.getLatencyP50Ms())).append(" | ")
                            .append(format(summary.getLatencyP95Ms())).append(" | ")
                            .append(format(summary.getLatencyP99Ms())).append(" | ")
                            .append(summary.getErrors()).append(" | ")
                            .append(summary.getTotal()).append(" |\n");
                });
        builder.append('\n');
        appendMetricsByK(builder, summaries);
    }

    private void appendMetricsByK(
            StringBuilder builder,
            Map<String, RecallBenchmarkReport.ModeSummary> summaries) {
        builder.append("## Metrics By K\n\n");
        builder.append("| Mode | K | Recall | Precision | MRR | NDCG |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        safeMap(summaries).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> safeMap(entry.getValue().getMetricsByK()).entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                        .forEach(metricEntry -> {
                            RecallBenchmarkReport.MetricAtK metric = metricEntry.getValue();
                            builder.append("| ")
                                    .append(entry.getKey()).append(" | ")
                                    .append(metricEntry.getKey()).append(" | ")
                                    .append(format(metric.getRecall())).append(" | ")
                                    .append(format(metric.getPrecision())).append(" | ")
                                    .append(format(metric.getReciprocalRank())).append(" | ")
                                    .append(format(metric.getNdcg())).append(" |\n");
                        }));
        builder.append('\n');
    }

    private void appendResults(StringBuilder builder, List<RecallBenchmarkReport.CaseResult> results) {
        builder.append("## Results\n\n");
        builder.append("| CaseId | Mode | Retrieval | Rerank | Reranker Type | Hit | All Expected | Recall@K | Precision@K | MRR | NDCG | Returned | Expected | Tiers | Latency (ms) | Error |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- | ---: | --- |\n");
        safeList(results).forEach(result -> builder.append("| ")
                .append(sanitize(result.getCaseId())).append(" | ")
                .append(sanitize(result.getMode())).append(" | ")
                .append(sanitize(result.getRetrievalMode())).append(" | ")
                .append(result.isRerankEnabled()).append(" | ")
                .append(result.getRerankerType() == null ? "" : result.getRerankerType().name()).append(" | ")
                .append(result.isRecallHit()).append(" | ")
                .append(result.isAllExpectedReturned()).append(" | ")
                .append(format(result.getRecallAtK())).append(" | ")
                .append(format(result.getPrecisionAtK())).append(" | ")
                .append(format(result.getReciprocalRank())).append(" | ")
                .append(format(result.getNdcg())).append(" | ")
                .append(sanitize(String.join(",", safeList(result.getReturnedFragmentIds())))).append(" | ")
                .append(sanitize(String.join(",", safeList(result.getExpectedFragmentIds())))).append(" | ")
                .append(sanitize(String.join(",", safeList(result.getReturnedTiers())))).append(" | ")
                .append(result.getLatencyMs()).append(" | ")
                .append(sanitize(result.getErrorMessage()))
                .append(" |\n"));
        builder.append('\n');
    }

    private void appendDiagnostics(StringBuilder builder, List<RecallBenchmarkReport.CaseResult> results) {
        builder.append("## Recall Diagnostics\n\n");
        builder.append("| CaseId | Mode | Rerank | Reranker Type | Model | Model Version | Model SHA-256 | Effect | Pool Strategy | Pool Limit | Preselection | Input | Output | Score Distinct | Rerank Latency (ms) | Changed Positions | TopK Membership Changed | Semantic Distinct | Keyword Distinct | Importance Distinct | Final Returned | L2 Search Accepted | L2 Fallback Accepted | Empty Reason |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        safeList(results).forEach(result -> {
            RecallDiagnostics diagnostics = result.getRecallDiagnostics();
            builder.append("| ")
                    .append(sanitize(result.getCaseId())).append(" | ")
                    .append(sanitize(result.getMode())).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.isRerankEnabled()).append(" | ")
                    .append(diagnostics == null || diagnostics.getRerankerType() == null
                            ? ""
                            : diagnostics.getRerankerType().name()).append(" | ")
                    .append(diagnostics == null ? "" : sanitize(diagnostics.getRerankModel())).append(" | ")
                    .append(diagnostics == null ? "" : sanitize(diagnostics.getRerankModelVersion())).append(" | ")
                    .append(diagnostics == null ? "" : sanitize(diagnostics.getRerankModelSha256())).append(" | ")
                    .append(diagnostics == null || diagnostics.getRerankEffectStatus() == null
                            ? ""
                            : diagnostics.getRerankEffectStatus().name()).append(" | ")
                    .append(diagnostics == null ? "" : sanitize(diagnostics.getRerankCandidatePoolStrategy())).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getRerankCandidatePoolLimit()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getRerankPreselectionCandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getRerankInputCandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getRerankOutputCandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getRerankScoreDistinctCount()).append(" | ")
                    .append(diagnostics == null ? "" : formatNanosAsMillis(diagnostics.getRerankLatencyNanos())).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getRerankChangedPositionCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getRerankTopKMembershipChangedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getSemanticScoreDistinctCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getKeywordScoreDistinctCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getImportanceDistinctCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getFinalReturnedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2SearchAcceptedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2NamespaceFallbackAcceptedCount()).append(" | ")
                    .append(diagnostics == null ? "" : sanitize(diagnostics.getEmptyRecallReason()))
                    .append(" |\n");
        });
        builder.append('\n');
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String formatNanosAsMillis(long nanos) {
        return format(nanos / 1_000_000.0d);
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    public record WrittenReport(Path jsonPath, Path markdownPath) {
    }
}
