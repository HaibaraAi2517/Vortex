package com.vortex.app.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningMemoryEvalReport {

    private Instant generatedAt;
    private String runId;
    private String profileId;
    private String datasetLocation;
    private String datasetVersion;
    private int scenarioCount;
    private int totalRecallCount;
    private int feedbackSubmitted;
    private boolean gatePassed;
    private LearningAggregate aggregate;

    @Builder.Default
    private List<GateCheck> gateChecks = List.of();

    @Builder.Default
    private List<ScenarioResult> scenarios = List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LearningAggregate {
        private long sampleCountBefore;
        private long sampleCountAfter;
        private long feedbackSampleCount;
        private long activeUpdateCountBefore;
        private long activeUpdateCountAfter;
        private int pendingRecallSessions;
        private double activeAverageNdcgBefore;
        private double activeAverageNdcgAfter;
        private double shadowAverageNdcgAfter;
        private double baselineAverageNdcgAfter;
        private double activeEvictionUtilityAfter;
        private double shadowEvictionUtilityAfter;
        private double baselineEvictionUtilityAfter;
        private double shadowRelativeLiftAfter;
        private double baselineRelativeLiftAfter;
        private double shadowWinRateAfter;
        private double baselineWinRateAfter;
        private double activeSelectionPrecisionAfter;
        private double activeSelectionCoverageAfter;
        private double medianRelevantRankBefore;
        private double medianRelevantRankAfter;
        private double firstCalibrationAverageNdcg;
        private double probeAverageNdcg;
        private int rankImprovedScenarioCount;
        private int ndcgImprovedScenarioCount;
        private double probeAllRelevantHitRate;
        private LearningProfileSnapshot beforeSnapshot;
        private LearningProfileSnapshot afterSnapshot;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LearningProfileSnapshot {
        private String activeProfileName;
        private Double activeAlpha;
        private Double activeBeta;
        private Double activeGamma;
        private Long activeUpdateCount;
        private String shadowProfileName;
        private Double shadowAlpha;
        private Double shadowBeta;
        private Double shadowGamma;
        private Long shadowUpdateCount;
        private Long shadowSampleCount;
        private Integer pendingRecallSessions;
        private Double activeAverageNdcg;
        private Double shadowAverageNdcg;
        private Double baselineAverageNdcg;
        private Double activeEvictionUtility;
        private Double shadowEvictionUtility;
        private Double baselineEvictionUtility;
        private Double shadowRelativeLift;
        private Double baselineRelativeLift;
        private Double shadowWinRate;
        private Double baselineWinRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioResult {
        private String scenarioId;
        private String namespace;
        private String actualNamespace;
        private String memoryScenario;
        private int fragmentCount;
        private int recallCount;
        private int feedbackSubmitted;

        @Builder.Default
        private List<String> relevantFragmentIds = List.of();

        private double beforeMedianRelevantRank;
        private double afterMedianRelevantRank;
        private double firstCalibrationNdcg;
        private double probeAverageNdcg;
        private boolean rankImproved;
        private boolean ndcgImproved;
        private double probeAllRelevantHitRate;
        private double activeSelectionPrecisionAfter;
        private double activeSelectionCoverageAfter;
        private long sampleCountBefore;
        private long sampleCountAfter;
        private long activeUpdateCountBefore;
        private long activeUpdateCountAfter;
        private double activeAverageNdcgBefore;
        private double activeAverageNdcgAfter;
        private double shadowAverageNdcgAfter;
        private double baselineAverageNdcgAfter;
        private double activeEvictionUtilityAfter;
        private double shadowEvictionUtilityAfter;
        private double baselineEvictionUtilityAfter;
        private double shadowRelativeLiftAfter;
        private double baselineRelativeLiftAfter;

        @Builder.Default
        private List<RecallObservation> observations = List.of();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecallObservation {
        private String phase;
        private String query;
        private String recallSessionId;
        private String activeProfileName;
        private String shadowProfileName;

        @Builder.Default
        private List<String> returnedFragmentIds = List.of();

        @Builder.Default
        private List<String> returnedActualFragmentIds = List.of();

        @Builder.Default
        private List<String> recalledFromTiers = List.of();

        private boolean allRelevantHit;
        private int relevantHitCount;
        private int relevantCount;
        private double selectionPrecision;
        private double selectionCoverage;
        private double medianRelevantRank;
        private double ndcg;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GateCheck {
        private String name;
        private boolean passed;
        private String expected;
        private String actual;
        private String details;
    }
}
