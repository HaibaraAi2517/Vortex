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
        appendModeSummary(builder, report.getModeSummaries());
        appendResults(builder, report.getResults());
        appendDiagnostics(builder, report.getResults());
        return builder.toString();
    }

    private void appendModeSummary(
            StringBuilder builder,
            Map<String, RecallBenchmarkReport.ModeSummary> summaries) {
        builder.append("## Mode Summary\n\n");
        builder.append("| Mode | Recall@K | Recall@K Lift vs Vector+Rerank | Recall@K Rel Lift vs Vector+Rerank | Case Hit Rate | All Expected Rate | Precision@K | MRR | NDCG | Avg Latency (ms) | Errors | Total |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
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
        builder.append("| CaseId | Mode | Retrieval | Rerank | Hit | All Expected | Recall@K | Precision@K | MRR | NDCG | Returned | Expected | Tiers | Latency (ms) | Error |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- | ---: | --- |\n");
        safeList(results).forEach(result -> builder.append("| ")
                .append(sanitize(result.getCaseId())).append(" | ")
                .append(sanitize(result.getMode())).append(" | ")
                .append(sanitize(result.getRetrievalMode())).append(" | ")
                .append(result.isRerankEnabled()).append(" | ")
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
        builder.append("| CaseId | Mode | Rerank | Empty Reason | Final Returned | Keyword Cand | Keyword Accepted | Vector Cand | Vector Accepted | Rerank Cand | L2 Search Accepted | L2 Fallback Accepted |\n");
        builder.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        safeList(results).forEach(result -> {
            RecallDiagnostics diagnostics = result.getRecallDiagnostics();
            builder.append("| ")
                    .append(sanitize(result.getCaseId())).append(" | ")
                    .append(sanitize(result.getMode())).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.isRerankEnabled()).append(" | ")
                    .append(diagnostics == null ? "" : sanitize(diagnostics.getEmptyRecallReason())).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getFinalReturnedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getKeywordCandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getKeywordAcceptedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getVectorCandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getVectorAcceptedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getRerankCandidateCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2SearchAcceptedCount()).append(" | ")
                    .append(diagnostics == null ? "" : diagnostics.getL2NamespaceFallbackAcceptedCount())
                    .append(" |\n");
        });
        builder.append('\n');
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
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