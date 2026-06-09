package com.vortex.app.eval;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class LearningMemoryEvalVerificationResult {

    private final String profileId;
    private final String datasetVersion;
    private final String reportPath;
    private final boolean passed;
    private final List<Drift> drifts;

    public String renderHumanReadable() {
        if (passed) {
            return "PASS: learning report '" + reportPath
                    + "' matches profile '" + profileId
                    + "' (dataset " + datasetVersion + ").";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("FAIL: learning report '")
                .append(reportPath)
                .append("' does not match profile '")
                .append(profileId)
                .append("' (dataset ")
                .append(datasetVersion)
                .append(").");
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
