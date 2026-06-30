package com.vortex.kernel.hmc;

import com.vortex.kernel.embedding.TokenCounter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MemorySummaryService {

    private final TokenCounter tokenCounter;
    private final int maxChars;

    public MemorySummaryService(
            @Qualifier("bgeSmallEmbeddingService") TokenCounter tokenCounter,
            @Value("${vortex.kernel.memory-pipeline.summary-max-chars:1200}") int maxChars) {
        this.tokenCounter = tokenCounter;
        this.maxChars = Math.max(64, maxChars);
    }

    public SummaryResult summarize(MemoryExtractionService.ExtractionResult extraction) {
        List<String> units = extraction == null || extraction.units() == null ? List.of() : extraction.units();
        String summary = units.isEmpty()
                ? ""
                : truncate(String.join(System.lineSeparator(), units));
        return new SummaryResult(summary, summary.isBlank() ? 0 : Math.max(1, tokenCounter.countTokens(summary)));
    }

    private String truncate(String text) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int end = Math.max(0, text.lastIndexOf(' ', maxChars));
        if (end < maxChars / 2) {
            end = maxChars;
        }
        return text.substring(0, end).strip();
    }

    public record SummaryResult(String summaryText, int tokenCount) {
    }
}
