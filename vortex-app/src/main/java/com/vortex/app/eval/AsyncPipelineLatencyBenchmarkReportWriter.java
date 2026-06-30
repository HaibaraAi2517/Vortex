package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
@RequiredArgsConstructor
public class AsyncPipelineLatencyBenchmarkReportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final LlmMemoryEvalProperties properties;

    public WrittenReport write(AsyncPipelineLatencyBenchmarkReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Async pipeline latency benchmark report must not be null");
        }
        Path outputDir = Path.of(properties.getReportOutputDir()).toAbsolutePath().normalize();
        String stamp = FILE_STAMP.format(report.getGeneratedAt());
        Path jsonPath = outputDir.resolve("async-pipeline-latency-benchmark-" + stamp + ".json");
        Path markdownPath = outputDir.resolve("async-pipeline-latency-benchmark-" + stamp + ".md");
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
            throw new IllegalStateException("Failed to write async pipeline latency benchmark report to " + outputDir, e);
        }
    }

    private String toMarkdown(AsyncPipelineLatencyBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Async Pipeline Latency Benchmark Report\n\n");
        builder.append("- GeneratedAt: ").append(report.getGeneratedAt()).append('\n');
        builder.append("- RunId: ").append(nullToEmpty(report.getRunId())).append('\n');
        builder.append("- RandomSeed: ").append(report.getRandomSeed()).append('\n');
        builder.append("- MainPathScope: ").append(sanitize(report.getMainPathScope())).append('\n');
        builder.append("- AsyncPipelineScope: ").append(sanitize(report.getAsyncPipelineScope())).append('\n');
        builder.append("- SuccessDefinition: ").append(sanitize(report.getSuccessDefinition())).append('\n');
        builder.append("- BenchmarkScope: ").append(sanitize(report.getBenchmarkScope())).append('\n');
        builder.append("- FragmentCount: ").append(report.getFragmentCount()).append('\n');
        builder.append("- WarmupFragmentCount: ").append(report.getWarmupFragmentCount()).append('\n');
        builder.append("- Modes: ").append(sanitize(String.join(", ", safeList(report.getModes())))).append('\n');
        builder.append("- SyncAverageMainPathLatencyMs: ")
                .append(format(report.getSyncAverageMainPathLatencyMs())).append('\n');
        builder.append("- AsyncAverageMainPathLatencyMs: ")
                .append(format(report.getAsyncAverageMainPathLatencyMs())).append('\n');
        builder.append("- RelativeMainPathLatencyReduction: ")
                .append(format(report.getRelativeMainPathLatencyReduction())).append('\n');
        builder.append("- PersistenceSuccessRate: ").append(format(report.getPersistenceSuccessRate())).append("\n\n");
        appendBackpressure(builder, report.getBackpressureSummary());
        appendModeSummary(builder, report.getModeSummaries());
        appendResults(builder, report.getResults());
        return builder.toString();
    }

    private void appendBackpressure(
            StringBuilder builder,
            AsyncPipelineLatencyBenchmarkReport.BackpressureSummary summary) {
        builder.append("## Backpressure Summary\n\n");
        if (summary == null) {
            builder.append("No backpressure summary recorded.\n\n");
            return;
        }
        builder.append("| Policy | Queue Capacity | Queue Size | Remaining | Active Workers | Max Workers | CallerRuns Total | CallerRuns During Benchmark | Max Observed Queue | Saturated | Probe Submitted | Probe Completed | Probe Errors | Probe Submit P95 (ms) | Probe Submit P99 (ms) | Probe Readiness P95 (ms) | Probe Readiness P99 (ms) |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        builder.append("| ")
                .append(sanitize(summary.getPolicy())).append(" | ")
                .append(summary.getQueueCapacity()).append(" | ")
                .append(summary.getQueueSize()).append(" | ")
                .append(summary.getQueueRemainingCapacity()).append(" | ")
                .append(summary.getActiveWorkers()).append(" | ")
                .append(summary.getMaxWorkers()).append(" | ")
                .append(summary.getCallerRunsCount()).append(" | ")
                .append(summary.getCallerRunsCountDuringBenchmark()).append(" | ")
                .append(summary.getMaxObservedQueueSize()).append(" | ")
                .append(summary.isSaturated()).append(" | ")
                .append(summary.getProbeSubmittedCount()).append(" | ")
                .append(summary.getProbeCompletedCount()).append(" | ")
                .append(summary.getProbeErrorCount()).append(" | ")
                .append(format(summary.getProbeSubmissionLatencyP95Ms())).append(" | ")
                .append(format(summary.getProbeSubmissionLatencyP99Ms())).append(" | ")
                .append(format(summary.getProbeReadinessLatencyP95Ms())).append(" | ")
                .append(format(summary.getProbeReadinessLatencyP99Ms())).append(" |\n\n");
    }

    private void appendModeSummary(
            StringBuilder builder,
            Map<String, AsyncPipelineLatencyBenchmarkReport.ModeSummary> summaries) {
        builder.append("## Mode Summary\n\n");
        builder.append("| Mode | Main P50 (ms) | Main P95 (ms) | Main P99 (ms) | Main Avg (ms) | Recall P95 (ms) | Prompt P95 (ms) | Write Submit P95 (ms) | Pipeline P95 (ms) | Pipeline Avg (ms) | Pipeline TPS | Readiness P95 (ms) | Readiness Lag Avg (ms) | Main Success Rate | Persistence Success Rate | Rerank Avg | Returned Avg | Errors | Total |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        safeMap(summaries).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    AsyncPipelineLatencyBenchmarkReport.ModeSummary summary = entry.getValue();
                    builder.append("| ")
                            .append(sanitize(entry.getKey())).append(" | ")
                            .append(format(summary.getMainPathLatencyP50Ms())).append(" | ")
                            .append(format(summary.getMainPathLatencyP95Ms())).append(" | ")
                            .append(format(summary.getMainPathLatencyP99Ms())).append(" | ")
                            .append(format(summary.getMainPathLatencyAverageMs())).append(" | ")
                            .append(format(summary.getRecallLatencyP95Ms())).append(" | ")
                            .append(format(summary.getPromptAssemblyLatencyP95Ms())).append(" | ")
                            .append(format(summary.getMemoryWriteSubmissionLatencyP95Ms())).append(" | ")
                            .append(format(summary.getAsyncPipelineLatencyP95Ms())).append(" | ")
                            .append(format(summary.getAsyncPipelineLatencyAverageMs())).append(" | ")
                            .append(format(summary.getAsyncPipelineThroughputPerSecond())).append(" | ")
                            .append(format(summary.getReadinessLatencyP95Ms())).append(" | ")
                            .append(format(summary.getReadinessLagAverageMs())).append(" | ")
                            .append(format(summary.getMainPathSuccessRate())).append(" | ")
                            .append(format(summary.getPersistenceSuccessRate())).append(" | ")
                            .append(format(summary.getRerankCandidateAverage())).append(" | ")
                            .append(format(summary.getReturnedFragmentAverage())).append(" | ")
                            .append(summary.getErrors()).append(" | ")
                            .append(summary.getTotal()).append(" |\n");
                });
        builder.append('\n');
    }

    private void appendResults(
            StringBuilder builder,
            List<AsyncPipelineLatencyBenchmarkReport.CaseResult> results) {
        builder.append("## Results\n\n");
        builder.append("| CaseId | Mode | Pipeline Status | Main Path (ms) | Recall (ms) | Prompt (ms) | Write Submit (ms) | Pipeline (ms) | Readiness (ms) | Returned | Rerank Candidates | L1 Candidates | L2 Search Candidates | Keyword Candidates | Main OK | Recall OK | Prompt OK | L2 Ready | L3 Ready | Persistence OK | Queue Before | Queue After | Backpressure | Error |\n");
        builder.append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- | --- | --- | ---: | ---: | --- | --- |\n");
        safeList(results).forEach(result -> builder.append("| ")
                .append(sanitize(result.getCaseId())).append(" | ")
                .append(sanitize(result.getMode())).append(" | ")
                .append(sanitize(result.getPipelineStatus())).append(" | ")
                .append(format(result.getMainPathLatencyMs())).append(" | ")
                .append(format(result.getRecallLatencyMs())).append(" | ")
                .append(format(result.getPromptAssemblyLatencyMs())).append(" | ")
                .append(format(result.getMemoryWriteSubmissionLatencyMs())).append(" | ")
                .append(format(result.getAsyncPipelineLatencyMs())).append(" | ")
                .append(format(result.getReadinessLatencyMs())).append(" | ")
                .append(result.getReturnedFragmentCount()).append(" | ")
                .append(result.getRerankCandidateCount()).append(" | ")
                .append(result.getL1CandidateCount()).append(" | ")
                .append(result.getL2SearchCandidateCount()).append(" | ")
                .append(result.getKeywordCandidateCount()).append(" | ")
                .append(result.isMainPathSucceeded()).append(" | ")
                .append(result.isRecallSucceeded()).append(" | ")
                .append(result.isPromptAssemblySucceeded()).append(" | ")
                .append(result.isL2Ready()).append(" | ")
                .append(result.isL3Ready()).append(" | ")
                .append(result.isPersistenceSucceeded()).append(" | ")
                .append(result.getQueueSizeBefore()).append(" | ")
                .append(result.getQueueSizeAfter()).append(" | ")
                .append(sanitize(result.getBackpressurePolicy())).append(" | ")
                .append(sanitize(result.getErrorMessage()))
                .append(" |\n"));
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