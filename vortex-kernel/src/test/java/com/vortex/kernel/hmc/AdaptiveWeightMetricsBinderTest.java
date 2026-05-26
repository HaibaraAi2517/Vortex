package com.vortex.kernel.hmc;

import com.vortex.common.dto.MemoryScenario;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveWeightMetricsBinderTest {

    @Test
    void bindRegistersLearningAndFeedbackMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(
                new ShadowEvaluationTracker(0.20, 14),
                0.05,
                100,
                0.3,
                0.5,
                0.2,
                14);
        AdaptiveWeightMetricsBinder binder = new AdaptiveWeightMetricsBinder(registry, learner);

        String sessionId = learner.recordRecallSession(RecallSessionRecord.builder()
                .scenario(MemoryScenario.CHAT)
                .rankedFragmentIds(List.of("a", "b"))
                .shadowRankedFragmentIds(List.of("b", "a"))
                .baselineRankedFragmentIds(List.of("a", "b"))
                .returnedFragmentIds(List.of("a"))
                .activeEvictionRankedFragmentIds(List.of("b"))
                .shadowEvictionRankedFragmentIds(List.of("b"))
                .baselineEvictionRankedFragmentIds(List.of("b"))
                .createdAt(Instant.now())
                .build());
        learner.recordFeedback(sessionId, Set.of("a"), true, 0.1);

        binder.bind();

        assertThat(registry.find("vortex.hmc.learning.weight")
                .tags("scenario", "chat", "profile", "active", "component", "alpha")
                .gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.learning.feedback.selection.precision")
                .tags("scenario", "chat", "profile", "active")
                .gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.learning.feedback.reward")
                .tags("scenario", "chat", "profile", "shadow")
                .gauge()).isNotNull();
        assertThat(registry.find("vortex.hmc.learning.shadow.relative.lift")
                .tags("scenario", "chat")
                .gauge()).isNotNull();
    }
}
