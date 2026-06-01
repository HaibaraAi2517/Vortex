package com.vortex.app.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vortex.common.dto.RecallDiagnostics;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMemoryEvalResult {

    private String caseId;
    private String mode;
    private String question;
    private String recallSessionId;

    @Builder.Default
    private List<String> returnedFragmentIds = List.of();

    @Builder.Default
    private List<String> recalledFromTiers = List.of();

    private String generatedAnswer;

    @JsonProperty("isCorrect")
    private boolean correct;

    private String failureReason;

    @Builder.Default
    private List<String> missingMustContain = List.of();

    @Builder.Default
    private List<String> matchedForbiddenTerms = List.of();

    private long latencyMs;
    private long storeLatencyMs;
    private long recoveryTargetWaitLatencyMs;
    private int recoveryTargetWaitPollCount;
    private long recoveryForceLatencyMs;
    private int recoveryForcePollCount;
    private int recoveryFillerFragmentsInserted;
    private long recallLatencyMs;
    private long promptAssemblyLatencyMs;
    private long generationLatencyMs;
    private long generationRequestBuildLatencyMs;
    private long generationHttpRoundTripLatencyMs;
    private long generationResponseParseLatencyMs;
    private long generationRetryBackoffLatencyMs;
    private long generationLatencyNanos;
    private long generationRequestBuildLatencyNanos;
    private long generationRequestSerializationLatencyNanos;
    private long generationHttpRequestBuildLatencyNanos;
    private long generationHttpRoundTripLatencyNanos;
    private long generationResponseParseLatencyNanos;
    private long generationResponseDecodeLatencyNanos;
    private long generationResponseJsonParseLatencyNanos;
    private long generationRetryBackoffLatencyNanos;
    private Integer generationAttemptCount;
    private Integer generationHttpStatusCode;
    private Integer generationRequestBytes;
    private Integer generationResponseBytes;
    private long feedbackLatencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private boolean recallHit;
    private RecallDiagnostics recallDiagnostics;
    private Boolean evictedBeforeAnswer;
    private Boolean feedbackSubmitted;

    @Builder.Default
    private List<String> feedbackUsedFragmentIds = List.of();

    private Long learningSampleCountBefore;
    private Long learningSampleCountAfter;
    private Long learningActiveUpdateCountBefore;
    private Long learningActiveUpdateCountAfter;
    private Double learningShadowLiftBefore;
    private Double learningShadowLiftAfter;
    private Double learningBaselineLiftBefore;
    private Double learningBaselineLiftAfter;
    private String errorMessage;
}
