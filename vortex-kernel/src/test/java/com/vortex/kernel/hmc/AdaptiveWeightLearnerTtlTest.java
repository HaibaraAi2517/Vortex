package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Ticker;
import com.vortex.common.dto.MemoryScenario;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveWeightLearnerTtlTest {

    @Test
    void expiredSessionsAreAutomaticallyEvicted() {
        ManualTicker ticker = new ManualTicker();
        AdaptiveWeightLearner learner = new AdaptiveWeightLearner(
                new ShadowEvaluationTracker(
                        0.20,
                        14,
                        10_000,
                        2_048,
                        null,
                        new ObjectMapper().findAndRegisterModules()),
                0.08,
                100,
                0.3,
                0.5,
                0.2,
                14,
                ticker);

        String sessionId = learner.recordRecallSession(RecallSessionRecord.builder()
                .scenario(MemoryScenario.CHAT)
                .rankedFragmentIds(List.of("winner"))
                .shadowRankedFragmentIds(List.of("winner"))
                .baselineRankedFragmentIds(List.of("winner"))
                .createdAt(Instant.now())
                .build());

        assertThat(learner.peekRecallSession(sessionId)).isNotNull();

        ticker.advance(Duration.ofMinutes(30).plusSeconds(1));

        assertThat(learner.peekRecallSession(sessionId)).isNull();
        learner.cleanUpPendingSessionsForTest();
        assertThat(learner.recallSessionsSizeForTest()).isZero();
    }

    private static final class ManualTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
