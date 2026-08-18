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

@Service
@RequiredArgsConstructor
public class AdmissionContentionBenchmarkReportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final LlmMemoryEvalProperties properties;

    public WrittenReport write(AdmissionContentionBenchmarkReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Admission contention benchmark report must not be null");
        }
        Path outputDir = Path.of(properties.getReportOutputDir()).toAbsolutePath().normalize();
        String stamp = FILE_STAMP.format(report.getGeneratedAt());
        Path jsonPath = outputDir.resolve("admission-contention-benchmark-" + stamp + ".json");
        Path markdownPath = outputDir.resolve("admission-contention-benchmark-" + stamp + ".md");
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
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to write admission contention benchmark report to " + outputDir,
                    exception);
        }
    }

    String toMarkdown(AdmissionContentionBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Admission Contention Benchmark Report\n\n");
        builder.append("- GeneratedAt: ").append(report.getGeneratedAt()).append('\n');
        builder.append("- RunId: ").append(sanitize(report.getRunId())).append('\n');
        builder.append("- BenchmarkScope: ").append(sanitize(report.getBenchmarkScope())).append('\n');
        builder.append("- SuccessDefinition: ").append(sanitize(report.getSuccessDefinition())).append('\n');
        builder.append("- OperationsPerThread: ").append(report.getOperationsPerThread()).append('\n');
        builder.append("- WarmupOperationsPerThread: ")
                .append(report.getWarmupOperationsPerThread()).append('\n');
        builder.append("- TokenCountPerFragment: ").append(report.getTokenCountPerFragment()).append('\n');
        builder.append("- ParallelismLevels: ")
                .append(sanitize(String.valueOf(report.getParallelismLevels()))).append("\n\n");

        builder.append("## Results\n\n");
        builder.append("| Threads | Attempted | Admitted | Success Rate | Throughput/s | Avg (ms) | P50 (ms) | P95 (ms) | P99 (ms) | Lock Acquisitions | Locks/Request | Lock Wait Avg (ms) | Lock Hold Avg (ms) | Planning Avg (ms) | Direct Attempts | Direct Commits | Direct Escalations | Direct Rejections | Optimistic Attempts | Conflicts | Conflict Rate | Fallbacks | Fallback Rate | Errors |\n");
        builder.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        safeList(report.getResults()).forEach(result -> builder.append("| ")
                .append(result.getParallelism()).append(" | ")
                .append(result.getAttempted()).append(" | ")
                .append(result.getAdmitted()).append(" | ")
                .append(format(result.getSuccessRate())).append(" | ")
                .append(format(result.getThroughputPerSecond())).append(" | ")
                .append(format(result.getLatencyAverageMs())).append(" | ")
                .append(format(result.getLatencyP50Ms())).append(" | ")
                .append(format(result.getLatencyP95Ms())).append(" | ")
                .append(format(result.getLatencyP99Ms())).append(" | ")
                .append(result.getLockAcquisitions()).append(" | ")
                .append(format(result.getLockAcquisitionsPerRequest())).append(" | ")
                .append(format(result.getLockWaitAverageMs())).append(" | ")
                .append(format(result.getLockHoldAverageMs())).append(" | ")
                .append(format(result.getPlanningAverageMs())).append(" | ")
                .append(result.getDirectAttempts()).append(" | ")
                .append(result.getDirectCommits()).append(" | ")
                .append(result.getDirectEscalations()).append(" | ")
                .append(result.getDirectRejections()).append(" | ")
                .append(result.getOptimisticAttempts()).append(" | ")
                .append(result.getOptimisticConflicts()).append(" | ")
                .append(format(result.getOptimisticConflictRate())).append(" | ")
                .append(result.getFallbacks()).append(" | ")
                .append(format(result.getFallbackRate())).append(" | ")
                .append(result.getErrors()).append(" |\n"));
        builder.append('\n');

        List<String> errors = safeList(report.getResults()).stream()
                .flatMap(result -> safeList(result.getErrorMessages()).stream())
                .toList();
        if (!errors.isEmpty()) {
            builder.append("## Errors\n\n");
            errors.forEach(error -> builder.append("- ").append(sanitize(error)).append('\n'));
            builder.append('\n');
        }
        return builder.toString();
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record WrittenReport(Path jsonPath, Path markdownPath) {
    }
}
