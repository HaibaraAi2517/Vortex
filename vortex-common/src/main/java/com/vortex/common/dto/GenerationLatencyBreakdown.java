package com.vortex.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationLatencyBreakdown {

    private long requestBuildLatencyMs;
    private long httpRoundTripLatencyMs;
    private long responseParseLatencyMs;
    private long retryBackoffLatencyMs;
    private long totalLatencyMs;
    private long requestBuildLatencyNanos;
    private long requestSerializationLatencyNanos;
    private long httpRequestBuildLatencyNanos;
    private long httpRoundTripLatencyNanos;
    private long responseParseLatencyNanos;
    private long responseDecodeLatencyNanos;
    private long responseJsonParseLatencyNanos;
    private long retryBackoffLatencyNanos;
    private long totalLatencyNanos;
    private int attemptCount;
    private int httpStatusCode;
    private int requestBytes;
    private int responseBytes;

    public long totalLatencyMs() {
        return totalLatencyMs > 0
                ? totalLatencyMs
                : requestBuildLatencyMs
                + httpRoundTripLatencyMs
                + responseParseLatencyMs
                + retryBackoffLatencyMs;
    }

    public long totalLatencyNanos() {
        return totalLatencyNanos > 0
                ? totalLatencyNanos
                : requestBuildLatencyNanos
                + httpRoundTripLatencyNanos
                + responseParseLatencyNanos
                + retryBackoffLatencyNanos;
    }
}
