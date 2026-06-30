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
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeRecoveryBenchmarkReportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final LlmMemoryEvalProperties properties;

    public WrittenReport write(RuntimeRecoveryBenchmarkReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Runtime recovery benchmark report must not be null");
        }
        Path outputDir = Path.of(properties.getReportOutputDir()).toAbsolutePath().normalize();
        String stamp = FILE_STAMP.format(report.getGeneratedAt());
        Path jsonPath = outputDir.resolve("runtime-recovery-benchmark-" + stamp + ".json");
        Path markdownPath = outputDir.resolve("runtime-recovery-benchmark-" + stamp + ".md");
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
            throw new IllegalStateException("Failed to write runtime recovery benchmark report to " + outputDir, e);
        }
    }

    private String toMarkdown(RuntimeRecoveryBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Runtime Recovery Benchmark Report\n\n");
        builder.append("- GeneratedAt: ").append(report.getGeneratedAt()).append('\n');
        builder.append("- RunId: ").append(nullToEmpty(report.getRunId())).append('\n');
        builder.append("- TotalCases: ").append(report.getTotalCases()).append('\n');
        builder.append("- PassedCases: ").append(report.getPassedCases()).append('\n');
        builder.append("- FailedCases: ").append(report.getFailedCases()).append('\n');
        builder.append("- SuccessRate: ").append(format(report.getSuccessRate())).append('\n');
        builder.append("- TotalLatencyMs: ").append(report.getTotalLatencyMs()).append('\n');
        builder.append("- AverageLatencyMs: ").append(format(report.getAverageLatencyMs())).append('\n');
        builder.append("- RandomSeed: ").append(report.getRandomSeed()).append('\n');
        builder.append("- SuccessDefinition: ").append(sanitize(report.getSuccessDefinition())).append("\n\n");
        appendCapabilities(builder, "Covered Capabilities", report.getCoveredCapabilities());
        appendCapabilities(builder, "Excluded Capabilities", report.getExcludedCapabilities());
        appendCategorySummaries(builder, report.getCategorySummaries());
        appendResults(builder, report.getResults());
        return builder.toString();
    }

    private void appendCapabilities(StringBuilder builder, String title, List<String> capabilities) {
        builder.append("## ").append(title).append("\n\n");
        safeList(capabilities).forEach(capability -> builder.append("- ")
                .append(sanitize(capability))
                .append('\n'));
        builder.append('\n');
    }

    private void appendCategorySummaries(
            StringBuilder builder,
            List<RuntimeRecoveryBenchmarkReport.CategorySummary> summaries) {
        builder.append("## Category Success Rates\n\n");
        builder.append("| Category | Total | Passed | Failed | SuccessRate | Average Latency (ms) |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        safeList(summaries).forEach(summary -> builder.append("| ")
                .append(sanitize(summary.getCategory())).append(" | ")
                .append(summary.getTotalCases()).append(" | ")
                .append(summary.getPassedCases()).append(" | ")
                .append(summary.getFailedCases()).append(" | ")
                .append(format(summary.getSuccessRate())).append(" | ")
                .append(format(summary.getAverageLatencyMs()))
                .append(" |\n"));
        builder.append('\n');
    }

    private void appendResults(StringBuilder builder, List<RuntimeRecoveryBenchmarkReport.CaseResult> results) {
        builder.append("## Results\n\n");
        builder.append("| CaseId | Category | Capability | Passed | TaskId | CheckpointId | Latency (ms) | Expected | Actual | Details | Error |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | ---: | --- | --- | --- | --- |\n");
        safeList(results).forEach(result -> builder.append("| ")
                .append(sanitize(result.getCaseId())).append(" | ")
                .append(sanitize(result.getCategory())).append(" | ")
                .append(sanitize(result.getCapability())).append(" | ")
                .append(result.isPassed()).append(" | ")
                .append(sanitize(result.getTaskId())).append(" | ")
                .append(sanitize(result.getCheckpointId())).append(" | ")
                .append(result.getLatencyMs()).append(" | ")
                .append(sanitize(result.getExpected())).append(" | ")
                .append(sanitize(result.getActual())).append(" | ")
                .append(sanitize(formatDetails(result.getDetails()))).append(" | ")
                .append(sanitize(result.getErrorMessage()))
                .append(" |\n"));
        builder.append('\n');
    }

    private String formatDetails(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        return details.entrySet().stream()
                .map(entry -> nullToEmpty(entry.getKey()) + "=" + nullToEmpty(entry.getValue()))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
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

    public record WrittenReport(Path jsonPath, Path markdownPath) {
    }
}

