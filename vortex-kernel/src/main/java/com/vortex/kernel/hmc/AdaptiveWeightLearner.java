package com.vortex.kernel.hmc;

import com.vortex.common.dto.MemoryScenario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AdaptiveWeightLearner {

    private final double learningRate;
    private final Map<MemoryScenario, ProfilePair> profiles = new EnumMap<>(MemoryScenario.class);
    private final ConcurrentHashMap<String, RecallSessionRecord> recallSessions = new ConcurrentHashMap<>();
    private final ShadowEvaluationTracker shadowEvaluationTracker;

    public AdaptiveWeightLearner(
            ShadowEvaluationTracker shadowEvaluationTracker,
            @Value("${vortex.kernel.learning.rate:0.05}") double learningRate,
            @Value("${vortex.kernel.eviction.alpha:0.3}") double defaultAlpha,
            @Value("${vortex.kernel.eviction.beta:0.5}") double defaultBeta,
            @Value("${vortex.kernel.eviction.gamma:0.2}") double defaultGamma) {
        this.shadowEvaluationTracker = shadowEvaluationTracker;
        this.learningRate = learningRate;
        for (MemoryScenario scenario : MemoryScenario.values()) {
            AdaptiveWeightProfile active = AdaptiveWeightProfile.builder()
                    .profileName(scenario.name().toLowerCase() + "-active")
                    .alpha(defaultAlpha)
                    .beta(defaultBeta)
                    .gamma(defaultGamma)
                    .updatedAt(Instant.now())
                    .build();
            active.normalize();
            AdaptiveWeightProfile shadow = active.copyAs(scenario.name().toLowerCase() + "-shadow");
            shadow.setAlpha(Math.min(0.90, active.getAlpha() + 0.05));
            shadow.setBeta(Math.max(0.05, active.getBeta() - 0.03));
            shadow.setGamma(Math.max(0.05, active.getGamma() - 0.02));
            shadow.normalize();
            profiles.put(scenario, new ProfilePair(active, shadow));
        }
    }

    public ProfileSelection selectProfiles(MemoryScenario scenario) {
        ProfilePair pair = profiles.getOrDefault(scenario, profiles.get(MemoryScenario.CHAT));
        return new ProfileSelection(pair.active, pair.shadow);
    }

    public String recordRecallSession(RecallSessionRecord session) {
        String sessionId = session.getSessionId() == null ? UUID.randomUUID().toString() : session.getSessionId();
        session.setSessionId(sessionId);
        recallSessions.put(sessionId, session);
        return sessionId;
    }

    public LearningSnapshot recordFeedback(
            String recallSessionId,
            Set<String> usedFragmentIds,
            boolean answerAccepted,
            double regretRate) {
        RecallSessionRecord session = recallSessions.remove(recallSessionId);
        if (session == null) {
            return null;
        }
        MemoryScenario scenario = session.getScenario();
        ProfilePair pair = profiles.getOrDefault(scenario, profiles.get(MemoryScenario.CHAT));

        double successSignal = answerAccepted ? 1.0 : -1.0;
        double groundingRatio = session.getRankedFragmentIds().isEmpty()
                ? 0.0
                : usedFragmentIds.stream().filter(session.getRankedFragmentIds()::contains).count()
                / (double) session.getRankedFragmentIds().size();
        double regretPenalty = Math.min(1.0, regretRate);

        pair.active.setBeta(pair.active.getBeta() + learningRate * successSignal * groundingRatio);
        pair.active.setAlpha(pair.active.getAlpha() - learningRate * regretPenalty);
        pair.active.setGamma(pair.active.getGamma() + learningRate * (answerAccepted ? 0.02 : -0.02));
        pair.active.normalize();
        pair.active.setUpdateCount(pair.active.getUpdateCount() + 1);
        pair.active.setUpdatedAt(Instant.now());

        shadowEvaluationTracker.recordEvaluation(
                scenario.name().toLowerCase(),
                session.getRankedFragmentIds(),
                session.getShadowRankedFragmentIds(),
                session.getBaselineRankedFragmentIds(),
                usedFragmentIds);

        ShadowEvaluationTracker.ShadowEvaluationSnapshot snapshot =
                shadowEvaluationTracker.snapshot(scenario.name().toLowerCase());
        if (snapshot.eligibleForPromotion()) {
            pair.active = pair.shadow.copyAs(scenario.name().toLowerCase() + "-active");
            pair.shadow = pair.active.copyAs(scenario.name().toLowerCase() + "-shadow");
            pair.shadow.setAlpha(Math.min(0.90, pair.active.getAlpha() + 0.04));
            pair.shadow.setBeta(Math.max(0.05, pair.active.getBeta() - 0.02));
            pair.shadow.normalize();
        }
        return snapshot(scenario);
    }

    public LearningSnapshot snapshot(MemoryScenario scenario) {
        ProfilePair pair = profiles.getOrDefault(scenario, profiles.get(MemoryScenario.CHAT));
        ShadowEvaluationTracker.ShadowEvaluationSnapshot shadow =
                shadowEvaluationTracker.snapshot(scenario.name().toLowerCase());
        return new LearningSnapshot(pair.active, pair.shadow, shadow, recallSessions.size());
    }

    public record ProfileSelection(AdaptiveWeightProfile active, AdaptiveWeightProfile shadow) {
    }

    public record LearningSnapshot(
            AdaptiveWeightProfile active,
            AdaptiveWeightProfile shadow,
            ShadowEvaluationTracker.ShadowEvaluationSnapshot shadowEvaluation,
            int pendingRecallSessions) {
    }

    private static final class ProfilePair {
        private AdaptiveWeightProfile active;
        private AdaptiveWeightProfile shadow;

        private ProfilePair(AdaptiveWeightProfile active, AdaptiveWeightProfile shadow) {
            this.active = active;
            this.shadow = shadow;
        }
    }
}
