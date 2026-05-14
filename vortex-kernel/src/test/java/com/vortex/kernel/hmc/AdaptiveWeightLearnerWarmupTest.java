package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.MemoryScenario;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveWeightLearnerWarmupTest {

    @Test
    void warmupPhaseUsesHighExploration() {
        AdaptiveWeightLearner learner = newLearner(100);

        driveRewards(learner, 50, 0);

        double[] probabilities = learner.armProbabilitiesForTest(MemoryScenario.CHAT);
        double maxProbability = max(probabilities);
        double normalizedEntropy = normalizedEntropy(probabilities);

        assertThat(maxProbability).isLessThan(0.35);
        assertThat(normalizedEntropy).isGreaterThan(0.80);
    }

    @Test
    void postWarmupConvergesToBestArm() {
        AdaptiveWeightLearner learner = newLearner(100);

        driveRewards(learner, 50, 0);
        double[] warmupProbabilities = learner.armProbabilitiesForTest(MemoryScenario.CHAT);
        double warmupBestProbability = max(warmupProbabilities);
        double warmupEntropy = normalizedEntropy(warmupProbabilities);

        driveRewards(learner, 100, 0);
        double[] probabilities = learner.armProbabilitiesForTest(MemoryScenario.CHAT);
        double postWarmupBestProbability = max(probabilities);
        double postWarmupEntropy = normalizedEntropy(probabilities);
        double average = 1.0 / probabilities.length;

        assertThat(indexOfMax(probabilities)).isZero();
        assertThat(postWarmupBestProbability).isGreaterThan(warmupBestProbability);
        assertThat(postWarmupBestProbability).isGreaterThan(average * 4.0);
        assertThat(postWarmupEntropy).isLessThan(warmupEntropy);
    }

    private AdaptiveWeightLearner newLearner(int warmupRecalls) {
        return new AdaptiveWeightLearner(
                new ShadowEvaluationTracker(
                        0.20,
                        14,
                        10_000,
                        2_048,
                        null,
                        new ObjectMapper().findAndRegisterModules()),
                0.20,
                warmupRecalls,
                0.3,
                0.5,
                0.2,
                14);
    }

    private void driveRewards(AdaptiveWeightLearner learner, int iterations, int favoredArmIndex) {
        for (int i = 0; i < iterations; i++) {
            double[] probabilities = learner.armProbabilitiesForTest(MemoryScenario.CHAT);
            double favoredProbability = probabilities[favoredArmIndex];
            String sessionId = learner.recordRecallSession(RecallSessionRecord.builder()
                    .scenario(MemoryScenario.CHAT)
                    .activeArmIndex(favoredArmIndex)
                    .shadowArmIndex(favoredArmIndex)
                    .activeSelectionProbability(favoredProbability)
                    .shadowSelectionProbability(favoredProbability)
                    .rankedFragmentIds(List.of("winner", "runner-up"))
                    .shadowRankedFragmentIds(List.of("winner", "runner-up"))
                    .baselineRankedFragmentIds(List.of("winner", "runner-up"))
                    .activeEvictionRankedFragmentIds(List.of("evict-safe"))
                    .shadowEvictionRankedFragmentIds(List.of("evict-safe"))
                    .baselineEvictionRankedFragmentIds(List.of("evict-safe"))
                    .createdAt(Instant.now())
                    .build());
            learner.recordFeedback(sessionId, Set.of("winner"), true, 0.0);
        }
    }

    private double max(double[] probabilities) {
        double max = Double.NEGATIVE_INFINITY;
        for (double probability : probabilities) {
            max = Math.max(max, probability);
        }
        return max;
    }

    private int indexOfMax(double[] probabilities) {
        int bestIndex = 0;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < probabilities.length; i++) {
            if (probabilities[i] > bestValue) {
                bestValue = probabilities[i];
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private double normalizedEntropy(double[] probabilities) {
        double entropy = 0.0;
        for (double probability : probabilities) {
            if (probability > 0.0) {
                entropy -= probability * Math.log(probability);
            }
        }
        return entropy / Math.log(probabilities.length);
    }
}
