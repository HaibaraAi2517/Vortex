package com.vortex.kernel.hmc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class MemoryExtractionService {

    private static final Pattern LINE_BOUNDARY = Pattern.compile("\\R+");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？.!?])\\s+");
    private static final Pattern BULLET_PREFIX = Pattern.compile("^\\s*[-*\\d.)]+\\s*");

    private final int maxUnits;

    public MemoryExtractionService(
            @Value("${vortex.kernel.memory-pipeline.extraction-max-units:12}") int maxUnits) {
        this.maxUnits = Math.max(1, maxUnits);
    }

    public ExtractionResult extract(String content) {
        if (content == null || content.isBlank()) {
            return new ExtractionResult(List.of(), "");
        }
        List<String> units = new ArrayList<>();
        for (String line : LINE_BOUNDARY.split(content.strip())) {
            addUnits(units, line);
            if (units.size() >= maxUnits) {
                break;
            }
        }
        if (units.isEmpty()) {
            String normalized = normalize(content);
            if (!normalized.isBlank()) {
                units.add(normalized);
            }
        }
        List<String> boundedUnits = units.stream()
                .limit(maxUnits)
                .toList();
        return new ExtractionResult(boundedUnits, String.join(System.lineSeparator(), boundedUnits));
    }

    private void addUnits(List<String> units, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String normalizedLine = normalize(line);
        if (normalizedLine.isBlank()) {
            return;
        }
        String[] sentences = SENTENCE_BOUNDARY.split(normalizedLine);
        for (String sentence : sentences) {
            String unit = normalize(sentence);
            if (!unit.isBlank()) {
                units.add(unit);
            }
            if (units.size() >= maxUnits) {
                return;
            }
        }
    }

    private String normalize(String value) {
        return BULLET_PREFIX.matcher(value == null ? "" : value)
                .replaceFirst("")
                .replaceAll("\\s+", " ")
                .strip();
    }

    public record ExtractionResult(List<String> units, String extractedText) {
    }
}
