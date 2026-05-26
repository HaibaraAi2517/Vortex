package com.vortex.kernel.hmc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.vortex.common.dto.MemoryScenario;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AdaptiveWeightLearner {

    private static final double ROLLBACK_DROP_THRESHOLD = 0.05;
    private static final double MIN_BANDIT_REWARD = 0.0;
    private static final double MAX_BANDIT_REWARD = 1.0;
    private static final double MIN_EXPLORATION = 0.05;
    private static final double MAX_EXPLORATION = 0.30;
    private static final double WARMUP_EXPLORATION = 0.30;
    private static final int MAX_STABLE_NO_IMPROVEMENT = 40;

    private final double learningRate;
    private final Duration rollbackWindow;
    private final int warmupRecalls;
    private final List<ArmDefinition> armCatalog;
    private final Map<MemoryScenario, ScenarioState> profiles = new EnumMap<>(MemoryScenario.class);
    private final Cache<String, RecallSessionRecord> recallSessions;
    private final ShadowEvaluationTracker shadowEvaluationTracker;
    private final Ticker recallSessionTicker;

    @Autowired
    public AdaptiveWeightLearner(
            ShadowEvaluationTracker shadowEvaluationTracker,
            @Value("${vortex.kernel.learning.rate:0.08}") double learningRate,
            @Value("${vortex.kernel.learning.warmup-recalls:100}") int warmupRecalls,
            @Value("${vortex.kernel.eviction.alpha:0.3}") double defaultAlpha,
            @Value("${vortex.kernel.eviction.beta:0.5}") double defaultBeta,
            @Value("${vortex.kernel.eviction.gamma:0.2}") double defaultGamma) {
        this(shadowEvaluationTracker, learningRate, warmupRecalls, defaultAlpha, defaultBeta, defaultGamma, 14, Ticker.systemTicker());
    }

    AdaptiveWeightLearner(
            ShadowEvaluationTracker shadowEvaluationTracker,
            double learningRate,
            int warmupRecalls,
            double defaultAlpha,
            double defaultBeta,
            double defaultGamma,
            long rollbackWindowDays) {
        this(shadowEvaluationTracker, learningRate, warmupRecalls, defaultAlpha, defaultBeta, defaultGamma, rollbackWindowDays, Ticker.systemTicker());
    }

    AdaptiveWeightLearner(
            ShadowEvaluationTracker shadowEvaluationTracker,
            double learningRate,
            int warmupRecalls,
            double defaultAlpha,
            double defaultBeta,
            double defaultGamma,
            long rollbackWindowDays,
            Ticker recallSessionTicker) {
        this.shadowEvaluationTracker = shadowEvaluationTracker;
        this.learningRate = learningRate;
        this.warmupRecalls = Math.max(1, warmupRecalls);
        this.rollbackWindow = Duration.ofDays(Math.max(1L, rollbackWindowDays));
        this.recallSessionTicker = recallSessionTicker == null ? Ticker.systemTicker() : recallSessionTicker;
        this.recallSessions = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .ticker(this.recallSessionTicker)
                .build();
        this.armCatalog = buildArmCatalog();
        int seedArmIndex = closestArmIndex(defaultAlpha, defaultBeta, defaultGamma);
        for (MemoryScenario scenario : MemoryScenario.values()) {
            ScenarioState state = new ScenarioState(scenario, armCatalog.size(), seedArmIndex);
            state.activeProbability = 1.0 / armCatalog.size();
            state.shadowProbability = 1.0 / armCatalog.size();
            state.initialize(toProfile(scenario, armCatalog.get(seedArmIndex), seedArmIndex, state.activeProbability, false));
            selectNextShadow(state);
            profiles.put(scenario, state);
        }
    }

    public ProfileSelection selectProfiles(MemoryScenario scenario) {
        ScenarioState state = scenarioState(scenario);
        synchronized (state) {
            return new ProfileSelection(
                    copyProfile(state.active, state.active.getProfileName()),
                    copyProfile(state.shadow, state.shadow.getProfileName()));
        }
    }

    public String recordRecallSession(RecallSessionRecord session) {
        String sessionId = session.getSessionId() == null ? UUID.randomUUID().toString() : session.getSessionId();
        session.setSessionId(sessionId);
        recallSessions.put(sessionId, session);
        return sessionId;
    }

    RecallSessionRecord peekRecallSession(String recallSessionId) {
        return recallSessions.getIfPresent(recallSessionId);
    }

    public LearningSnapshot recordFeedback(
            String recallSessionId,
            Set<String> usedFragmentIds,
            boolean answerAccepted,
            double regretRate) {
        RecallSessionRecord session = recallSessions.asMap().remove(recallSessionId);
        if (session == null) {
            return null;
        }
        MemoryScenario scenario = session.getScenario() == null ? MemoryScenario.CHAT : session.getScenario();
        ScenarioState state = scenarioState(scenario);
        String scenarioKey = scenario.name().toLowerCase();

        FeedbackSignals signals = buildFeedbackSignals(session, usedFragmentIds, answerAccepted, regretRate);
        shadowEvaluationTracker.recordEvaluation(
                scenarioKey,
                session.getRankedFragmentIds(),
                session.getShadowRankedFragmentIds(),
                session.getBaselineRankedFragmentIds(),
                session.getActiveEvictionRankedFragmentIds(),
                session.getShadowEvictionRankedFragmentIds(),
                session.getBaselineEvictionRankedFragmentIds(),
                usedFragmentIds);
        ShadowEvaluationTracker.ShadowEvaluationSnapshot trackerSnapshot = shadowEvaluationTracker.snapshot(scenarioKey);

        synchronized (state) {
            state.totalRecalls++;
            state.lastFeedback = FeedbackMetricState.from(signals);
            updateBandit(state, session, signals);
            boolean promoted = false;
            if (state.deployment == null || state.deployment.state != DeploymentStatus.SHADOW_PROMOTED) {
                promoted = maybePromoteShadow(scenario, state, trackerSnapshot);
            }
            if (!promoted) {
                maybeRollback(scenario, state, signals, trackerSnapshot);
            }
            if (state.deployment == null || state.deployment.state != DeploymentStatus.SHADOW_PROMOTED) {
                selectNextShadow(state);
            }
            return buildSnapshot(state, trackerSnapshot);
        }
    }

    public LearningSnapshot snapshot(MemoryScenario scenario) {
        MemoryScenario resolvedScenario = scenario == null ? MemoryScenario.CHAT : scenario;
        ScenarioState state = scenarioState(resolvedScenario);
        ShadowEvaluationTracker.ShadowEvaluationSnapshot shadow =
                shadowEvaluationTracker.snapshot(resolvedScenario.name().toLowerCase());
        synchronized (state) {
            return buildSnapshot(state, shadow);
        }
    }

    LearningMetricsSnapshot metricsSnapshot(MemoryScenario scenario) {
        MemoryScenario resolvedScenario = scenario == null ? MemoryScenario.CHAT : scenario;
        ScenarioState state = scenarioState(resolvedScenario);
        ShadowEvaluationTracker.ShadowEvaluationSnapshot shadow =
                shadowEvaluationTracker.snapshot(resolvedScenario.name().toLowerCase());
        synchronized (state) {
            FeedbackMetricState feedback = state.lastFeedback == null ? FeedbackMetricState.empty() : state.lastFeedback;
            return new LearningMetricsSnapshot(
                    copyProfile(state.active, state.active.getProfileName()),
                    copyProfile(state.shadow, state.shadow.getProfileName()),
                    shadow,
                    state.currentExploration,
                    state.totalRecalls,
                    Math.toIntExact(recallSessions.estimatedSize()),
                    feedback.answerReward(),
                    feedback.regretPenalty(),
                    feedback.activeGrounding(),
                    feedback.shadowGrounding(),
                    feedback.baselineGrounding(),
                    feedback.activeSelectionPrecision(),
                    feedback.shadowSelectionPrecision(),
                    feedback.baselineSelectionPrecision(),
                    feedback.activeSelectionCoverage(),
                    feedback.shadowSelectionCoverage(),
                    feedback.baselineSelectionCoverage(),
                    feedback.activeReward(),
                    feedback.shadowReward(),
                    feedback.baselineReward());
        }
    }

    double[] armProbabilitiesForTest(MemoryScenario scenario) {
        ScenarioState state = scenarioState(scenario);
        synchronized (state) {
            return distribution(state);
        }
    }

    long recallSessionsSizeForTest() {
        return recallSessions.estimatedSize();
    }

    void cleanUpPendingSessionsForTest() {
        recallSessions.cleanUp();
    }

    /** Test-only: clears pending recall sessions for deterministic test isolation. */
    void clearPendingSessionsForTest() {
        recallSessions.invalidateAll();
    }

    private void updateBandit(ScenarioState state, RecallSessionRecord session, FeedbackSignals signals) {
        int activeArmIndex = session.getActiveArmIndex() == null ? state.activeArmIndex : session.getActiveArmIndex();
        int shadowArmIndex = session.getShadowArmIndex() == null ? state.shadowArmIndex : session.getShadowArmIndex();
        double activeProbability = clampProbability(session.getActiveSelectionProbability());
        double shadowProbability = clampProbability(session.getShadowSelectionProbability());

        applyExp3Update(state, activeArmIndex, activeProbability, signals.activeReward());
        applyExp3Update(state, shadowArmIndex, shadowProbability, signals.shadowReward());

        if (signals.activeReward() >= state.bestObservedReward + 1.0e-6) {
            state.bestObservedReward = signals.activeReward();
            state.stableRoundsWithoutImprovement = 0;
        } else {
            state.stableRoundsWithoutImprovement++;
        }
        double[] probabilities = distribution(state);
        int bestIndex = bestArmIndex(probabilities);
        state.active = toProfile(state.scenario, armCatalog.get(bestIndex), bestIndex, probabilities[bestIndex], false);
        state.activeArmIndex = bestIndex;
        state.active.setUpdateCount(state.active.getUpdateCount() + 1);
        state.active.setUpdatedAt(Instant.now());
        state.currentExploration = nextExploration(state);
    }

    private void applyExp3Update(ScenarioState state, int armIndex, double probability, double reward) {
        if (armIndex < 0 || armIndex >= state.armWeights.length) {
            return;
        }
        double boundedReward = clamp(reward, MIN_BANDIT_REWARD, MAX_BANDIT_REWARD);
        double estimatedReward = boundedReward / probability;
        double scaledLearningRate = effectiveLearningRate(state.active.getUpdateCount());
        double exponent = (scaledLearningRate * estimatedReward) / state.armWeights.length;
        state.armWeights[armIndex] = state.armWeights[armIndex] * Math.exp(exponent);
    }

    private boolean maybePromoteShadow(
            MemoryScenario scenario,
            ScenarioState state,
            ShadowEvaluationTracker.ShadowEvaluationSnapshot trackerSnapshot) {
        if (!trackerSnapshot.eligibleForPromotion()) {
            return false;
        }
        Instant now = Instant.now();
        state.previousActive = copyProfile(state.active, activeName(scenario));
        state.activeArmIndex = state.shadowArmIndex;
        state.active = toProfile(scenario, armCatalog.get(state.activeArmIndex), state.activeArmIndex, state.activeProbability, false);
        state.active.setUpdatedAt(now);
        state.active.setUpdateCount(state.active.getUpdateCount() + 1);
        state.deployment = DeploymentState.builder()
                .state(DeploymentStatus.SHADOW_PROMOTED)
                .promotedAt(now)
                .promotionSampleCount(trackerSnapshot.sampleCount())
                .prePromotionRelativeLift(trackerSnapshot.relativeLift())
                .prePromotionActiveAverageNdcg(trackerSnapshot.activeAverageNdcg())
                .build();
        state.currentExploration = Math.max(MIN_EXPLORATION, state.currentExploration * 0.85);
        selectNextShadow(state);
        shadowEvaluationTracker.resetScenario(scenario.name().toLowerCase());
        return true;
    }

    private void maybeRollback(
            MemoryScenario scenario,
            ScenarioState state,
            FeedbackSignals signals,
            ShadowEvaluationTracker.ShadowEvaluationSnapshot trackerSnapshot) {
        if (state.deployment == null || state.deployment.state != DeploymentStatus.SHADOW_PROMOTED) {
            return;
        }
        double activeGrounding = clamp01(state.deployment.prePromotionActiveAverageNdcg());
        boolean degradedAnswer = !signals.answerAccepted() && signals.shadowReward() >= signals.baselineReward();
        boolean degradedRelativeLift = trackerSnapshot.relativeLift() <= -ROLLBACK_DROP_THRESHOLD;
        boolean degradedGrounding = activeGrounding > 0.0
                && signals.shadowGrounding() < activeGrounding - ROLLBACK_DROP_THRESHOLD;
        boolean rollbackExpired =
                Duration.between(state.deployment.promotedAt, Instant.now()).compareTo(rollbackWindow) > 0;

        if (!rollbackExpired && (degradedAnswer || degradedRelativeLift || degradedGrounding)) {
            if (state.previousActive != null) {
                int previousIndex = closestArmIndex(
                        state.previousActive.getAlpha(),
                        state.previousActive.getBeta(),
                        state.previousActive.getGamma());
                state.activeArmIndex = previousIndex;
                state.active = toProfile(scenario, armCatalog.get(previousIndex), previousIndex, state.activeProbability, false);
                state.active.setUpdatedAt(Instant.now());
            }
            state.currentExploration = Math.min(MAX_EXPLORATION, state.currentExploration * 1.15);
            state.deployment = DeploymentState.builder()
                    .state(DeploymentStatus.ROLLED_BACK)
                    .promotedAt(state.deployment.promotedAt)
                    .rolledBackAt(Instant.now())
                    .promotionSampleCount(state.deployment.promotionSampleCount)
                    .prePromotionRelativeLift(state.deployment.prePromotionRelativeLift)
                    .prePromotionActiveAverageNdcg(state.deployment.prePromotionActiveAverageNdcg)
                    .build();
            selectNextShadow(state);
            shadowEvaluationTracker.resetScenario(scenario.name().toLowerCase());
            return;
        }
        if (rollbackExpired) {
            state.previousActive = null;
            state.deployment = DeploymentState.builder()
                    .state(DeploymentStatus.STABLE)
                    .promotedAt(state.deployment.promotedAt)
                    .promotionSampleCount(state.deployment.promotionSampleCount)
                    .prePromotionRelativeLift(state.deployment.prePromotionRelativeLift)
                    .prePromotionActiveAverageNdcg(state.deployment.prePromotionActiveAverageNdcg)
                    .build();
        }
    }

    private void selectNextShadow(ScenarioState state) {
        double[] distribution = distribution(state);
        int bestArmIndex = bestArmIndex(distribution);
        int shadowCandidate = secondBestArmIndex(distribution, bestArmIndex);
        if (shadowCandidate == bestArmIndex) {
            shadowCandidate = diversifiedArmIndex(state, distribution, bestArmIndex);
        }
        state.shadowArmIndex = shadowCandidate;
        state.shadowProbability = distribution[shadowCandidate];
        state.activeProbability = distribution[bestArmIndex];
        state.shadow = toProfile(state.scenario, armCatalog.get(shadowCandidate), shadowCandidate, state.shadowProbability, true);
    }

    private int diversifiedArmIndex(ScenarioState state, double[] distribution, int bestArmIndex) {
        double maxDistance = Double.NEGATIVE_INFINITY;
        int selected = bestArmIndex;
        ArmDefinition best = armCatalog.get(bestArmIndex);
        for (int i = 0; i < distribution.length; i++) {
            if (i == bestArmIndex) {
                continue;
            }
            double distance = best.distanceTo(armCatalog.get(i)) * distribution[i];
            if (distance > maxDistance) {
                maxDistance = distance;
                selected = i;
            }
        }
        return selected;
    }

    private double[] distribution(ScenarioState state) {
        double[] probabilities = new double[state.armWeights.length];
        double weightSum = 0.0;
        for (double weight : state.armWeights) {
            weightSum += weight;
        }
        if (weightSum <= 0.0) {
            weightSum = state.armWeights.length;
        }
        double exploration = effectiveExploration(state);
        for (int i = 0; i < state.armWeights.length; i++) {
            probabilities[i] = ((1.0 - exploration) * (state.armWeights[i] / weightSum))
                    + (exploration / state.armWeights.length);
        }
        return probabilities;
    }

    private int bestArmIndex(ScenarioState state) {
        return bestArmIndex(distribution(state));
    }

    private int bestArmIndex(double[] distribution) {
        int bestIndex = 0;
        double bestProbability = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < distribution.length; i++) {
            if (distribution[i] > bestProbability) {
                bestProbability = distribution[i];
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private int secondBestArmIndex(ScenarioState state, int bestArmIndex) {
        return secondBestArmIndex(distribution(state), bestArmIndex);
    }

    private int secondBestArmIndex(double[] distribution, int bestArmIndex) {
        int secondBest = bestArmIndex;
        double secondProbability = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < distribution.length; i++) {
            if (i == bestArmIndex) {
                continue;
            }
            if (distribution[i] > secondProbability) {
                secondProbability = distribution[i];
                secondBest = i;
            }
        }
        return secondBest;
    }

    private FeedbackSignals buildFeedbackSignals(
            RecallSessionRecord session,
            Set<String> usedFragmentIds,
            boolean answerAccepted,
            double regretRate) {
        int returnedCount = session.getReturnedFragmentIds() == null ? 0 : session.getReturnedFragmentIds().size();
        double activeGrounding = ratio(session.getRankedFragmentIds(), usedFragmentIds);
        double shadowGrounding = ratio(session.getShadowRankedFragmentIds(), usedFragmentIds);
        double baselineGrounding = ratio(session.getBaselineRankedFragmentIds(), usedFragmentIds);
        session.setActiveGroundingScore(activeGrounding);
        session.setShadowGroundingScore(shadowGrounding);
        session.setBaselineGroundingScore(baselineGrounding);

        double activeRecallReward = rankingReward(session.getRankedFragmentIds(), usedFragmentIds);
        double shadowRecallReward = rankingReward(session.getShadowRankedFragmentIds(), usedFragmentIds);
        double baselineRecallReward = rankingReward(session.getBaselineRankedFragmentIds(), usedFragmentIds);
        double activeEvictionReward = evictionReward(session.getActiveEvictionRankedFragmentIds(), usedFragmentIds);
        double shadowEvictionReward = evictionReward(session.getShadowEvictionRankedFragmentIds(), usedFragmentIds);
        double baselineEvictionReward = evictionReward(session.getBaselineEvictionRankedFragmentIds(), usedFragmentIds);
        double activeSelectionPrecision = selectionPrecision(session.getRankedFragmentIds(), usedFragmentIds, returnedCount);
        double shadowSelectionPrecision = selectionPrecision(session.getShadowRankedFragmentIds(), usedFragmentIds, returnedCount);
        double baselineSelectionPrecision = selectionPrecision(session.getBaselineRankedFragmentIds(), usedFragmentIds, returnedCount);
        double activeSelectionCoverage = selectionCoverage(session.getRankedFragmentIds(), usedFragmentIds, returnedCount);
        double shadowSelectionCoverage = selectionCoverage(session.getShadowRankedFragmentIds(), usedFragmentIds, returnedCount);
        double baselineSelectionCoverage = selectionCoverage(session.getBaselineRankedFragmentIds(), usedFragmentIds, returnedCount);
        double answerReward = answerAccepted ? 1.0 : 0.0;
        double regretPenalty = 1.0 - clamp01(regretRate);

        double activeReward = reward(
                answerReward,
                regretPenalty,
                activeRecallReward,
                activeEvictionReward,
                activeGrounding,
                activeSelectionPrecision,
                activeSelectionCoverage);
        double shadowReward = reward(
                answerReward,
                regretPenalty,
                shadowRecallReward,
                shadowEvictionReward,
                shadowGrounding,
                shadowSelectionPrecision,
                shadowSelectionCoverage);
        double baselineReward = reward(
                answerReward,
                regretPenalty,
                baselineRecallReward,
                baselineEvictionReward,
                baselineGrounding,
                baselineSelectionPrecision,
                baselineSelectionCoverage);
        return new FeedbackSignals(
                answerAccepted,
                answerReward,
                regretPenalty,
                activeGrounding,
                shadowGrounding,
                baselineGrounding,
                activeSelectionPrecision,
                shadowSelectionPrecision,
                baselineSelectionPrecision,
                activeSelectionCoverage,
                shadowSelectionCoverage,
                baselineSelectionCoverage,
                activeReward,
                shadowReward,
                baselineReward);
    }

    private double reward(
            double answerReward,
            double regretPenalty,
            double recallReward,
            double evictionReward,
            double grounding,
            double selectionPrecision,
            double selectionCoverage) {
        double composite = (answerReward * 0.15)
                + (regretPenalty * 0.15)
                + (recallReward * 0.25)
                + (evictionReward * 0.10)
                + (grounding * 0.10)
                + (selectionPrecision * 0.15)
                + (selectionCoverage * 0.10);
        return clamp(composite, MIN_BANDIT_REWARD, MAX_BANDIT_REWARD);
    }

    private double selectionPrecision(List<String> ranking, Set<String> usedFragmentIds, int returnedCount) {
        if (ranking == null || ranking.isEmpty() || usedFragmentIds == null || usedFragmentIds.isEmpty() || returnedCount <= 0) {
            return 0.0;
        }
        int limit = Math.min(returnedCount, ranking.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (usedFragmentIds.contains(ranking.get(i))) {
                hits++;
            }
        }
        return hits / (double) limit;
    }

    private double selectionCoverage(List<String> ranking, Set<String> usedFragmentIds, int returnedCount) {
        if (ranking == null || ranking.isEmpty() || usedFragmentIds == null || usedFragmentIds.isEmpty() || returnedCount <= 0) {
            return 0.0;
        }
        int limit = Math.min(returnedCount, ranking.size());
        Set<String> window = new HashSet<>(limit);
        for (int i = 0; i < limit; i++) {
            window.add(ranking.get(i));
        }
        int hits = 0;
        for (String usedFragmentId : usedFragmentIds) {
            if (window.contains(usedFragmentId)) {
                hits++;
            }
        }
        return hits / (double) usedFragmentIds.size();
    }

    private double rankingReward(List<String> ranking, Set<String> usedFragmentIds) {
        if (ranking == null || ranking.isEmpty() || usedFragmentIds == null || usedFragmentIds.isEmpty()) {
            return 0.0;
        }
        double dcg = 0.0;
        int idealHits = Math.min(ranking.size(), usedFragmentIds.size());
        for (int i = 0; i < ranking.size(); i++) {
            if (usedFragmentIds.contains(ranking.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private double evictionReward(List<String> evictionRanking, Set<String> usedFragmentIds) {
        if (evictionRanking == null || evictionRanking.isEmpty()) {
            return 0.0;
        }
        int penalties = 0;
        for (String fragmentId : evictionRanking) {
            if (usedFragmentIds != null && usedFragmentIds.contains(fragmentId)) {
                penalties++;
            }
        }
        return 1.0 - (penalties / (double) evictionRanking.size());
    }

    private double ratio(Iterable<String> rankedIds, Set<String> usedFragmentIds) {
        if (rankedIds == null || usedFragmentIds == null || usedFragmentIds.isEmpty()) {
            return 0.0;
        }
        int total = 0;
        int hits = 0;
        for (String rankedId : rankedIds) {
            total++;
            if (usedFragmentIds.contains(rankedId)) {
                hits++;
            }
        }
        return total == 0 ? 0.0 : hits / (double) total;
    }

    private AdaptiveWeightProfile toProfile(
            MemoryScenario scenario,
            ArmDefinition arm,
            int armIndex,
            double selectionProbability,
            boolean shadow) {
        AdaptiveWeightProfile profile = AdaptiveWeightProfile.builder()
                .profileName(profileName(scenario, shadow, armIndex, selectionProbability))
                .alpha(arm.alpha())
                .beta(arm.beta())
                .gamma(arm.gamma())
                .updatedAt(Instant.now())
                .build();
        profile.normalize();
        return profile;
    }

    private double effectiveLearningRate(long updateCount) {
        return learningRate / Math.sqrt(Math.max(1L, updateCount + 1L));
    }

    private double nextExploration(ScenarioState state) {
        if (state.stableRoundsWithoutImprovement >= MAX_STABLE_NO_IMPROVEMENT) {
            return Math.min(MAX_EXPLORATION, state.currentExploration * 1.1);
        }
        return Math.max(MIN_EXPLORATION, state.currentExploration * 0.97);
    }

    private int closestArmIndex(double alpha, double beta, double gamma) {
        int bestIndex = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < armCatalog.size(); i++) {
            double distance = armCatalog.get(i).distanceTo(alpha, beta, gamma);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private List<ArmDefinition> buildArmCatalog() {
        List<ArmDefinition> arms = new ArrayList<>();
        double[] grid = {0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80};
        for (double alpha : grid) {
            for (double beta : grid) {
                double gamma = 1.0 - alpha - beta;
                if (gamma < 0.05 || gamma > 0.90) {
                    continue;
                }
                AdaptiveWeightProfile profile = AdaptiveWeightProfile.builder()
                        .profileName("arm")
                        .alpha(alpha)
                        .beta(beta)
                        .gamma(gamma)
                        .build();
                profile.normalize();
                arms.add(new ArmDefinition(profile.getAlpha(), profile.getBeta(), profile.getGamma()));
            }
        }
        arms.sort(Comparator.comparingDouble(ArmDefinition::alpha)
                .thenComparingDouble(ArmDefinition::beta)
                .thenComparingDouble(ArmDefinition::gamma));
        return arms;
    }

    private ScenarioState scenarioState(MemoryScenario scenario) {
        MemoryScenario resolved = scenario == null ? MemoryScenario.CHAT : scenario;
        return profiles.getOrDefault(resolved, profiles.get(MemoryScenario.CHAT));
    }

    private LearningSnapshot buildSnapshot(
            ScenarioState state,
            ShadowEvaluationTracker.ShadowEvaluationSnapshot shadowSnapshot) {
        return new LearningSnapshot(
                copyProfile(state.active, state.active.getProfileName()),
                copyProfile(state.shadow, state.shadow.getProfileName()),
                shadowSnapshot,
                Math.toIntExact(recallSessions.estimatedSize()),
                copyDeployment(state.deployment));
    }

    private double effectiveExploration(ScenarioState state) {
        if (state.totalRecalls < warmupRecalls) {
            return WARMUP_EXPLORATION;
        }
        return clamp(state.currentExploration, MIN_EXPLORATION, MAX_EXPLORATION);
    }

    private AdaptiveWeightProfile copyProfile(AdaptiveWeightProfile profile, String profileName) {
        return profile == null ? null : profile.copyAs(profileName);
    }

    private DeploymentState copyDeployment(DeploymentState deployment) {
        if (deployment == null) {
            return null;
        }
        return DeploymentState.builder()
                .state(deployment.state())
                .promotedAt(deployment.promotedAt())
                .rolledBackAt(deployment.rolledBackAt())
                .promotionSampleCount(deployment.promotionSampleCount())
                .prePromotionRelativeLift(deployment.prePromotionRelativeLift())
                .prePromotionActiveAverageNdcg(deployment.prePromotionActiveAverageNdcg())
                .build();
    }

    private double clampProbability(Double probability) {
        if (probability == null || probability.isNaN() || probability <= 0.0) {
            return 1.0 / Math.max(1, armCatalog.size());
        }
        return Math.max(probability, 1.0 / Math.max(1, armCatalog.size() * 10.0));
    }

    private double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String activeName(MemoryScenario scenario) {
        return scenario.name().toLowerCase() + "-active";
    }

    private String shadowName(MemoryScenario scenario) {
        return scenario.name().toLowerCase() + "-shadow";
    }

    private String profileName(MemoryScenario scenario, boolean shadow, int armIndex, double probability) {
        String base = shadow ? shadowName(scenario) : activeName(scenario);
        long probabilityCode = Math.round(probability * 10_000.0);
        return base + "-arm" + armIndex + "-p" + probabilityCode;
    }

    public record ProfileSelection(AdaptiveWeightProfile active, AdaptiveWeightProfile shadow) {
    }

    public record LearningSnapshot(
            AdaptiveWeightProfile active,
            AdaptiveWeightProfile shadow,
            ShadowEvaluationTracker.ShadowEvaluationSnapshot shadowEvaluation,
            int pendingRecallSessions,
            DeploymentState deployment) {
    }

    record LearningMetricsSnapshot(
            AdaptiveWeightProfile active,
            AdaptiveWeightProfile shadow,
            ShadowEvaluationTracker.ShadowEvaluationSnapshot shadowEvaluation,
            double exploration,
            int totalRecalls,
            int pendingRecallSessions,
            double answerReward,
            double regretPenalty,
            double activeGrounding,
            double shadowGrounding,
            double baselineGrounding,
            double activeSelectionPrecision,
            double shadowSelectionPrecision,
            double baselineSelectionPrecision,
            double activeSelectionCoverage,
            double shadowSelectionCoverage,
            double baselineSelectionCoverage,
            double activeReward,
            double shadowReward,
            double baselineReward) {
    }

    @lombok.Builder
    public record DeploymentState(
            DeploymentStatus state,
            Instant promotedAt,
            Instant rolledBackAt,
            long promotionSampleCount,
            double prePromotionRelativeLift,
            double prePromotionActiveAverageNdcg) {
    }

    public enum DeploymentStatus {
        STABLE,
        SHADOW_PROMOTED,
        ROLLED_BACK
    }

    private static final class ScenarioState {
        // INVARIANT: all field accesses must hold monitor(this).
        private final MemoryScenario scenario;
        private final double[] armWeights;
        private AdaptiveWeightProfile active;
        private AdaptiveWeightProfile shadow;
        private AdaptiveWeightProfile previousActive;
        private DeploymentState deployment;
        private int activeArmIndex;
        private int shadowArmIndex;
        private double activeProbability;
        private double shadowProbability;
        private double currentExploration = 0.18;
        private double bestObservedReward;
        private int stableRoundsWithoutImprovement;
        private int totalRecalls;
        private FeedbackMetricState lastFeedback = FeedbackMetricState.empty();

        private ScenarioState(MemoryScenario scenario, int armCount, int seedArmIndex) {
            this.scenario = scenario;
            this.armWeights = new double[armCount];
            for (int i = 0; i < armCount; i++) {
                armWeights[i] = 1.0;
            }
            this.activeArmIndex = seedArmIndex;
            this.shadowArmIndex = seedArmIndex;
            this.activeProbability = 1.0 / armCount;
            this.shadowProbability = 1.0 / armCount;
        }

        private void initialize(AdaptiveWeightProfile active) {
            this.active = active;
            this.previousActive = active.copyAs(active.getProfileName());
            this.deployment = DeploymentState.builder()
                    .state(DeploymentStatus.STABLE)
                    .build();
        }
    }

    private record ArmDefinition(double alpha, double beta, double gamma) {
        private double distanceTo(ArmDefinition other) {
            return distanceTo(other.alpha, other.beta, other.gamma);
        }

        private double distanceTo(double otherAlpha, double otherBeta, double otherGamma) {
            return Math.abs(alpha - otherAlpha) + Math.abs(beta - otherBeta) + Math.abs(gamma - otherGamma);
        }
    }

    private record FeedbackSignals(
            boolean answerAccepted,
            double answerReward,
            double regretPenalty,
            double activeGrounding,
            double shadowGrounding,
            double baselineGrounding,
            double activeSelectionPrecision,
            double shadowSelectionPrecision,
            double baselineSelectionPrecision,
            double activeSelectionCoverage,
            double shadowSelectionCoverage,
            double baselineSelectionCoverage,
            double activeReward,
            double shadowReward,
            double baselineReward) {
    }

    private record FeedbackMetricState(
            double answerReward,
            double regretPenalty,
            double activeGrounding,
            double shadowGrounding,
            double baselineGrounding,
            double activeSelectionPrecision,
            double shadowSelectionPrecision,
            double baselineSelectionPrecision,
            double activeSelectionCoverage,
            double shadowSelectionCoverage,
            double baselineSelectionCoverage,
            double activeReward,
            double shadowReward,
            double baselineReward) {
        private static FeedbackMetricState empty() {
            return new FeedbackMetricState(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        private static FeedbackMetricState from(FeedbackSignals signals) {
            return new FeedbackMetricState(
                    signals.answerReward(),
                    signals.regretPenalty(),
                    signals.activeGrounding(),
                    signals.shadowGrounding(),
                    signals.baselineGrounding(),
                    signals.activeSelectionPrecision(),
                    signals.shadowSelectionPrecision(),
                    signals.baselineSelectionPrecision(),
                    signals.activeSelectionCoverage(),
                    signals.shadowSelectionCoverage(),
                    signals.baselineSelectionCoverage(),
                    signals.activeReward(),
                    signals.shadowReward(),
                    signals.baselineReward());
        }
    }
}
