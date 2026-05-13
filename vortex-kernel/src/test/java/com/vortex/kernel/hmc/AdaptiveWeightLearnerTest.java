package com.vortex.kernel.hmc;

import com.vortex.common.dto.MemoryScenario;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveWeightLearnerTest {

    @Test
    void feedbackUsesUnifiedSignalAndUpdatesWeightsWithoutBreakingNormalization() {
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(
                new ShadowEvaluationTracker(0.20, 14),
                0.05,
                100,
                0.3,
                0.5,
                0.2,
                14);

        String sessionId = learner.recordRecallSession(RecallSessionRecord.builder()
                .scenario(MemoryScenario.CODING)
                .rankedFragmentIds(List.of("a", "b"))
                .shadowRankedFragmentIds(List.of("b", "a"))
                .baselineRankedFragmentIds(List.of("a", "b"))
                .createdAt(Instant.now())
                .build());

        AdaptiveWeightLearner.LearningSnapshot snapshot =
                learner.recordFeedback(sessionId, Set.of("a"), true, 0.1);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.active().getUpdateCount()).isEqualTo(1);
        assertThat(snapshot.active().getAlpha()
                + snapshot.active().getBeta()
                + snapshot.active().getGamma()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(snapshot.deployment().state()).isEqualTo(AdaptiveWeightLearner.DeploymentStatus.STABLE);
        assertThat(learner.peekRecallSession(sessionId)).isNull();
    }

    @Test
    void promotionCreatesRollbackGuardAndFailedFollowupRollsBackToPreviousActive() {
        ShadowEvaluationTracker tracker = new ShadowEvaluationTracker(0.20, 0);
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(
                tracker,
                0.05,
                100,
                0.3,
                0.5,
                0.2,
                14);

        String promotedSession = learner.recordRecallSession(RecallSessionRecord.builder()
                .scenario(MemoryScenario.CHAT)
                .rankedFragmentIds(List.of("a", "b"))
                .shadowRankedFragmentIds(List.of("b", "a"))
                .baselineRankedFragmentIds(List.of("a", "b"))
                .createdAt(Instant.now())
                .build());
        learner.recordFeedback(promotedSession, Set.of("b"), true, 0.0);

        AdaptiveWeightLearner.LearningSnapshot promoted = learner.snapshot(MemoryScenario.CHAT);
        assertThat(promoted.deployment().state()).isEqualTo(AdaptiveWeightLearner.DeploymentStatus.SHADOW_PROMOTED);
        double promotedAlpha = promoted.active().getAlpha();

        String rollbackSession = learner.recordRecallSession(RecallSessionRecord.builder()
                .scenario(MemoryScenario.CHAT)
                .rankedFragmentIds(List.of("a", "b"))
                .shadowRankedFragmentIds(List.of("b", "a"))
                .baselineRankedFragmentIds(List.of("a", "b"))
                .createdAt(Instant.now())
                .build());
        AdaptiveWeightLearner.LearningSnapshot rolledBack =
                learner.recordFeedback(rollbackSession, Set.of("b"), false, 0.9);

        assertThat(rolledBack.deployment().state()).isEqualTo(AdaptiveWeightLearner.DeploymentStatus.ROLLED_BACK);
        assertThat(rolledBack.active().getProfileName()).contains("-active-arm");
        assertThat(rolledBack.active().getProfileName()).isNotEqualTo(promoted.active().getProfileName());
        assertThat(rolledBack.shadow().getProfileName()).contains("-shadow-arm");
    }

    @Test
    void selectProfilesReturnsDetachedCopiesAndShadowTracksLatestActiveDirection() {
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(
                new ShadowEvaluationTracker(0.20, 14),
                0.05,
                100,
                0.3,
                0.5,
                0.2,
                14);

        AdaptiveWeightLearner.ProfileSelection initial = learner.selectProfiles(MemoryScenario.SEARCH);
        double initialShadowAlpha = initial.shadow().getAlpha();
        initial.active().setAlpha(0.9);

        String sessionId = learner.recordRecallSession(RecallSessionRecord.builder()
                .scenario(MemoryScenario.SEARCH)
                .rankedFragmentIds(List.of("a", "b", "c"))
                .shadowRankedFragmentIds(List.of("b", "a", "c"))
                .baselineRankedFragmentIds(List.of("a", "b", "c"))
                .createdAt(Instant.now())
                .build());

        AdaptiveWeightLearner.LearningSnapshot snapshot =
                learner.recordFeedback(sessionId, Set.of("a", "b"), true, 0.0);

        AdaptiveWeightLearner.ProfileSelection afterUpdate = learner.selectProfiles(MemoryScenario.SEARCH);
        assertThat(snapshot.active().getAlpha()).isNotEqualTo(0.9);
        assertThat(afterUpdate.shadow().getAlpha()).isNotEqualTo(initialShadowAlpha);
        assertThat(afterUpdate.shadow().getProfileName()).contains("-shadow-arm");
    }
}
