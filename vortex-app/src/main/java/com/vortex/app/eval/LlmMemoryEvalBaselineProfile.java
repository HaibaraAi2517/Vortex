package com.vortex.app.eval;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum LlmMemoryEvalBaselineProfile {

    OFFICIAL_V2_STRICT(
            "official-v2-strict",
            "20260529-real-bge-v2-006",
            "v2",
            "classpath:llm-memory-eval-set-v2.json",
            true,
            "Single-run strict official baseline for exact 15/15 reproduction",
            List.of(
                    ModeExpectation.of("Baseline-NoMemory", 0, 15, null, null, null),
                    ModeExpectation.of("Vortex-Memory", 15, 15, 1.0d, null, null),
                    ModeExpectation.of("Vortex-RecoveredMemory", 15, 15, 1.0d, 1.0d, 1.0d))),

    OFFICIAL_V2_1_STRICT(
            "official-v2.1-strict",
            "20260601-v2-009-contract-audit-5x-net",
            "v2.1",
            "classpath:llm-memory-eval-set-v2-1.json",
            true,
            "Official strict baseline for explicit v2.1 time-offset contract",
            List.of(
                    ModeExpectation.of("Baseline-NoMemory", 0, 15, null, null, null),
                    ModeExpectation.of("Vortex-Memory", 15, 15, 1.0d, null, null),
                    ModeExpectation.of("Vortex-RecoveredMemory", 15, 15, 1.0d, 1.0d, 1.0d))),

    CONTRACT_V2_1_CANDIDATE(
            "contract-v2.1-candidate",
            "20260601-v2-009-contract-audit-5x-net",
            "v2.1",
            "classpath:llm-memory-eval-set-v2-1.json",
            true,
            "Transition alias for official-v2.1-strict",
            List.of(
                    ModeExpectation.of("Baseline-NoMemory", 0, 15, null, null, null),
                    ModeExpectation.of("Vortex-Memory", 15, 15, 1.0d, null, null),
                    ModeExpectation.of("Vortex-RecoveredMemory", 15, 15, 1.0d, 1.0d, 1.0d))),

    OFFICIAL_V2_1_EXTENDED_STRICT(
            "official-v2.1-extended-strict",
            "20260602-v2-1-extended-candidate-audit-generation-retry-001",
            "v2.1-extended",
            "classpath:llm-memory-eval-set-v2-1-extended.json",
            true,
            "Official strict baseline for 30-case v2.1 extended memory eval",
            List.of(
                    ModeExpectation.of("Baseline-NoMemory", 0, 30, null, null, null),
                    ModeExpectation.of("Vortex-Memory", 30, 30, 1.0d, null, null),
                    ModeExpectation.of("Vortex-RecoveredMemory", 30, 30, 1.0d, 1.0d, 1.0d))),

    CANDIDATE_V2_1_EXTENDED(
            "candidate-v2.1-extended",
            "candidate-v2.1-extended",
            "v2.1-extended",
            "classpath:llm-memory-eval-set-v2-1-extended.json",
            false,
            "Candidate 30-case v2.1 extension for broader memory capability audit",
            List.of()),

    OFFICIAL_V3_REAL_AGENT_WORKLOAD_STRICT(
            "official-v3-real-agent-workload-strict",
            "20260603-v3-real-agent-workload-audit-002",
            "v3-real-agent-workload",
            "classpath:llm-memory-eval-set-v3-real-agent-workload.json",
            true,
            "Official strict baseline for 12-case realistic long-task Agent memory eval",
            List.of(
                    ModeExpectation.of("Baseline-NoMemory", 0, 12, null, null, null),
                    ModeExpectation.of("Vortex-Memory", 12, 12, 1.0d, null, null),
                    ModeExpectation.of("Vortex-RecoveredMemory", 12, 12, 1.0d, 1.0d, 1.0d))),

    AUDIT_V3_REAL_AGENT_WORKLOAD(
            "audit-v3-real-agent-workload",
            "candidate-v3-real-agent-workload",
            "v3-real-agent-workload",
            "classpath:llm-memory-eval-set-v3-real-agent-workload.json",
            false,
            "Candidate audit-only workload for realistic long-task Agent memory behavior",
            List.of()),

    AUDIT_V2_STABILITY(
            "audit-v2-stability",
            "20260601-mode-scoped-l2-wait-audit-5x-net",
            "v2",
            "classpath:llm-memory-eval-set-v2.json",
            false,
            "Multi-run stability gate profile for v2 memory/recovered audit",
            List.of());

    private static final List<LlmMemoryEvalBaselineProfile> PROFILES = List.of(
            OFFICIAL_V2_STRICT,
            AUDIT_V2_STABILITY,
            OFFICIAL_V2_1_STRICT,
            CONTRACT_V2_1_CANDIDATE,
            OFFICIAL_V2_1_EXTENDED_STRICT,
            CANDIDATE_V2_1_EXTENDED,
            OFFICIAL_V3_REAL_AGENT_WORKLOAD_STRICT,
            AUDIT_V3_REAL_AGENT_WORKLOAD);

    private static final Map<String, LlmMemoryEvalBaselineProfile> BY_ID = Map.of(
            OFFICIAL_V2_STRICT.id, OFFICIAL_V2_STRICT,
            OFFICIAL_V2_1_STRICT.id, OFFICIAL_V2_1_STRICT,
            CONTRACT_V2_1_CANDIDATE.id, CONTRACT_V2_1_CANDIDATE,
            OFFICIAL_V2_1_EXTENDED_STRICT.id, OFFICIAL_V2_1_EXTENDED_STRICT,
            AUDIT_V2_STABILITY.id, AUDIT_V2_STABILITY,
            CANDIDATE_V2_1_EXTENDED.id, CANDIDATE_V2_1_EXTENDED,
            OFFICIAL_V3_REAL_AGENT_WORKLOAD_STRICT.id, OFFICIAL_V3_REAL_AGENT_WORKLOAD_STRICT,
            AUDIT_V3_REAL_AGENT_WORKLOAD.id, AUDIT_V3_REAL_AGENT_WORKLOAD);

    private final String id;
    private final String baselineId;
    private final String datasetVersion;
    private final String datasetLocation;
    private final boolean strictReportProfile;
    private final String description;
    private final List<ModeExpectation> modeExpectations;

    LlmMemoryEvalBaselineProfile(
            String id,
            String baselineId,
            String datasetVersion,
            String datasetLocation,
            boolean strictReportProfile,
            String description,
            List<ModeExpectation> modeExpectations) {
        this.id = id;
        this.baselineId = baselineId;
        this.datasetVersion = datasetVersion;
        this.datasetLocation = datasetLocation;
        this.strictReportProfile = strictReportProfile;
        this.description = description;
        this.modeExpectations = List.copyOf(modeExpectations);
    }

    public String id() {
        return id;
    }

    public String baselineId() {
        return baselineId;
    }

    public String datasetVersion() {
        return datasetVersion;
    }

    public String datasetLocation() {
        return datasetLocation;
    }

    public boolean strictReportProfile() {
        return strictReportProfile;
    }

    public String description() {
        return description;
    }

    public List<ModeExpectation> modeExpectations() {
        return modeExpectations;
    }

    public static List<LlmMemoryEvalBaselineProfile> allProfiles() {
        return PROFILES;
    }

    public static LlmMemoryEvalBaselineProfile require(String profileId) {
        String normalized = normalizeProfileId(profileId);
        LlmMemoryEvalBaselineProfile profile = BY_ID.get(normalized);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown LLM memory eval baseline profile: " + profileId);
        }
        return profile;
    }

    public static Optional<LlmMemoryEvalBaselineProfile> find(String profileId) {
        String normalized = normalizeProfileId(profileId);
        return Optional.ofNullable(BY_ID.get(normalized));
    }

    public static String inferDatasetVersion(String datasetLocation) {
        if ("classpath:llm-memory-eval-set-v3-real-agent-workload.json".equals(datasetLocation)) {
            return "v3-real-agent-workload";
        }
        if ("classpath:llm-memory-eval-set-v2-1-extended.json".equals(datasetLocation)) {
            return "v2.1-extended";
        }
        if ("classpath:llm-memory-eval-set-v2-1.json".equals(datasetLocation)) {
            return "v2.1";
        }
        if ("classpath:llm-memory-eval-set-v2.json".equals(datasetLocation)) {
            return "v2";
        }
        if ("classpath:llm-memory-eval-set.json".equals(datasetLocation)) {
            return "v1";
        }
        return "custom";
    }

    public static String inferAuditProfileId(String datasetLocation) {
        if ("classpath:llm-memory-eval-set-v3-real-agent-workload.json".equals(datasetLocation)) {
            return OFFICIAL_V3_REAL_AGENT_WORKLOAD_STRICT.id();
        }
        if ("classpath:llm-memory-eval-set-v2-1-extended.json".equals(datasetLocation)) {
            return OFFICIAL_V2_1_EXTENDED_STRICT.id();
        }
        if ("classpath:llm-memory-eval-set-v2-1.json".equals(datasetLocation)) {
            return OFFICIAL_V2_1_STRICT.id();
        }
        if ("classpath:llm-memory-eval-set-v2.json".equals(datasetLocation)) {
            return AUDIT_V2_STABILITY.id();
        }
        return "custom";
    }

    public static String inferStrictVerifierProfileId(String datasetLocation) {
        if ("classpath:llm-memory-eval-set-v3-real-agent-workload.json".equals(datasetLocation)) {
            return OFFICIAL_V3_REAL_AGENT_WORKLOAD_STRICT.id();
        }
        if ("classpath:llm-memory-eval-set-v2-1-extended.json".equals(datasetLocation)) {
            return OFFICIAL_V2_1_EXTENDED_STRICT.id();
        }
        if ("classpath:llm-memory-eval-set-v2-1.json".equals(datasetLocation)) {
            return OFFICIAL_V2_1_STRICT.id();
        }
        if ("classpath:llm-memory-eval-set-v2.json".equals(datasetLocation)) {
            return OFFICIAL_V2_STRICT.id();
        }
        return "";
    }

    private static String normalizeProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return OFFICIAL_V2_STRICT.id;
        }
        return profileId.trim().toLowerCase(Locale.ROOT);
    }

    public record ModeExpectation(
            String modeName,
            int expectedCorrect,
            int expectedTotal,
            Double expectedAccuracy,
            Double expectedRecoveredAccuracy,
            Double expectedRecoveredL2HitRate) {

        private static ModeExpectation of(
                String modeName,
                int expectedCorrect,
                int expectedTotal,
                Double expectedAccuracy,
                Double expectedRecoveredAccuracy,
                Double expectedRecoveredL2HitRate) {
            return new ModeExpectation(
                    modeName,
                    expectedCorrect,
                    expectedTotal,
                    expectedAccuracy,
                    expectedRecoveredAccuracy,
                    expectedRecoveredL2HitRate);
        }
    }
}
