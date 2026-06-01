package com.vortex.app.eval;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class LlmMemoryEvalBaselineVerificationResult {

    private final String baselineId;
    private final String reportPath;
    private final boolean passed;
    private final List<Drift> drifts;

    public String renderHumanReadable() {
        if (passed) {
            return "PASS: report '" + reportPath
                    + "' still matches official LLM memory eval baseline '" + baselineId + "'.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("FAIL: report '")
                .append(reportPath)
                .append("' drifted from official LLM memory eval baseline '")
                .append(baselineId)
                .append("'.");
        for (Drift drift : drifts) {
            builder.append(System.lineSeparator())
                    .append("- ")
                    .append(drift.field())
                    .append(" expected=")
                    .append(drift.expected())
                    .append(" actual=")
                    .append(drift.actual());
        }
        return builder.toString();
    }

    public record Drift(String field, String expected, String actual) {
    }
}
