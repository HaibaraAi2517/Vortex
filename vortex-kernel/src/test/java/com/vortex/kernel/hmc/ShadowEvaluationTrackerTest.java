package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowEvaluationTrackerTest {

    @Test
    void promotionWindowStartRemainsVisibleAcrossConcurrentRecordAndSnapshot() throws Exception {
        ShadowEvaluationTracker tracker = new ShadowEvaluationTracker(0.20, 0);
        int writers = 4;
        int iterations = 200;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    for (int j = 0; j < iterations; j++) {
                        tracker.recordEvaluation(
                                "chat",
                                List.of("a", "b", "c"),
                                List.of("b", "a", "c"),
                                List.of("a", "b", "c"),
                                Set.of("b"));
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }

        ShadowEvaluationTracker.ShadowEvaluationSnapshot snapshot = tracker.snapshot("chat");
        assertThat(snapshot.sampleCount()).isEqualTo((long) writers * iterations);
        assertThat(snapshot.promotionWindowStart()).isNotNull();
        assertThat(snapshot.promotionWindowStart()).isBeforeOrEqualTo(Instant.now());
        assertThat(snapshot.eligibleForPromotion()).isTrue();
    }

    @Test
    void trackerPersistsStateAndComputesBaselineSustainedRatio() throws Exception {
        Path stateFile = Files.createTempFile("shadow-eval", ".json");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ShadowEvaluationTracker tracker = new ShadowEvaluationTracker(
                0.20,
                0,
                1,
                64,
                stateFile,
                objectMapper);
        tracker.loadPersistedState();

        tracker.recordEvaluation(
                "coding",
                List.of("a", "b", "c"),
                List.of("a", "b", "c"),
                List.of("c", "b", "a"),
                List.of("c", "b", "a"),
                List.of("c", "b", "a"),
                List.of("a", "b", "c"),
                Set.of("a"));

        ShadowEvaluationTracker reloaded = new ShadowEvaluationTracker(
                0.20,
                0,
                1,
                64,
                stateFile,
                objectMapper);
        reloaded.loadPersistedState();

        ShadowEvaluationTracker.ShadowEvaluationSnapshot snapshot = reloaded.snapshot("coding");
        assertThat(snapshot.sampleCount()).isEqualTo(1);
        assertThat(snapshot.baselineRelativeLift()).isGreaterThan(0.0);
        assertThat(snapshot.baselineWinRate()).isGreaterThan(0.0);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
