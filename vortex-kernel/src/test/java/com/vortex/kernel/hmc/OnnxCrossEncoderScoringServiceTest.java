package com.vortex.kernel.hmc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OnnxCrossEncoderScoringServiceTest {

    private static final String MODEL_SHA = "a".repeat(64);
    private static final CrossEncoderScoringService.ModelMetadata METADATA =
            new CrossEncoderScoringService.ModelMetadata(
                    "cross-encoder/test-model",
                    "0123456789abcdef0123456789abcdef01234567",
                    MODEL_SHA);

    @Test
    void preservesDocumentOrderAcrossBatches() {
        List<List<String>> observedBatches = new ArrayList<>();
        OnnxCrossEncoderScoringService.BatchScorer scorer = (query, documents) -> {
            observedBatches.add(documents);
            return documents.stream()
                    .map(document -> Double.parseDouble(document.substring("doc-".length())))
                    .toList();
        };

        try (ServiceHandle handle = service(2, Duration.ofSeconds(1), scorer)) {
            assertThat(handle.service().score(
                    "query",
                    List.of("doc-1", "doc-2", "doc-3", "doc-4", "doc-5")))
                    .containsExactly(1.0, 2.0, 3.0, 4.0, 5.0);
            assertThat(observedBatches).containsExactly(
                    List.of("doc-1", "doc-2"),
                    List.of("doc-3", "doc-4"),
                    List.of("doc-5"));
        }
    }

    @Test
    void exposesPinnedModelMetadata() {
        try (ServiceHandle handle = service(
                4,
                Duration.ofSeconds(1),
                (query, documents) -> documents.stream().map(ignored -> 1.0).toList())) {
            assertThat(handle.service().metadata()).isEqualTo(METADATA);
        }
    }

    @Test
    void rejectsWrongScoreCount() {
        try (ServiceHandle handle = service(
                4,
                Duration.ofSeconds(1),
                (query, documents) -> List.of(1.0))) {
            assertThatThrownBy(() -> handle.service().score("query", List.of("one", "two")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cross-encoder scoring failed")
                    .hasRootCauseMessage("Cross-encoder provider returned 1 scores for 2 documents");
        }
    }

    @Test
    void rejectsNonFiniteScore() {
        try (ServiceHandle handle = service(
                4,
                Duration.ofSeconds(1),
                (query, documents) -> List.of(Double.NaN))) {
            assertThatThrownBy(() -> handle.service().score("query", List.of("one")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Cross-encoder provider returned a non-finite score at index 0");
        }
    }

    @Test
    void terminatesProviderAndFailsWhenTimeoutExpires() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        OnnxCrossEncoderScoringService.BatchScorer scorer =
                new OnnxCrossEncoderScoringService.BatchScorer() {
                    @Override
                    public List<Double> scoreBatch(String query, List<String> documents) {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("provider interrupted", e);
                        }
                        return List.of(1.0);
                    }

                    @Override
                    public void cancel() {
                        cancelled.set(true);
                        release.countDown();
                    }
                };

        try (ServiceHandle handle = service(1, Duration.ofMillis(25), scorer)) {
            assertThatThrownBy(() -> handle.service().score("query", List.of("one")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("timed out after 25 ms");
            assertThat(cancelled).isTrue();
        }
    }

    @Test
    void propagatesProviderFailureAsCause() {
        IllegalArgumentException providerFailure = new IllegalArgumentException("provider failed");
        try (ServiceHandle handle = service(
                4,
                Duration.ofSeconds(1),
                (query, documents) -> {
                    throw providerFailure;
                })) {
            assertThatThrownBy(() -> handle.service().score("query", List.of("one")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cross-encoder scoring failed")
                    .hasCause(providerFailure);
        }
    }

    @Test
    void failsInitializationWhenModelArtifactIsMissing(@TempDir Path tempDir) {
        OnnxCrossEncoderScoringService service = productionService(tempDir);

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cross-encoder model file is missing");
    }

    @Test
    void rejectsInvalidMetadataBeforeScoring() {
        assertThatThrownBy(() -> new OnnxCrossEncoderScoringService(
                new CrossEncoderScoringService.ModelMetadata("model", "version", "not-a-sha"),
                1,
                Duration.ofSeconds(1),
                (query, documents) -> List.of(1.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256 must contain 64 hex characters");
    }

    @Test
    void scoresWithPinnedOnnxArtifactWhenModelPathIsProvided() {
        String configuredPath = System.getProperty("vortex.cross-encoder.model-path", "");
        Path modelPath = Path.of(configuredPath);
        assumeTrue(!configuredPath.isBlank() && Files.isDirectory(modelPath));

        OnnxCrossEncoderScoringService service = new OnnxCrossEncoderScoringService(
                modelPath.toString(),
                "cross-encoder/ms-marco-MiniLM-L6-v2",
                "c5ee24cb16019beea0893ab7796b1df96625c6b8",
                "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9",
                "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
                16,
                512,
                8,
                TimeUnit.SECONDS.toMillis(5));
        try {
            service.init();
            List<Double> scores = service.score(
                    "What is the capital of France?",
                    List.of(
                            "Paris is the capital and largest city of France.",
                            "Bananas are elongated edible fruits produced by flowering plants."));

            assertThat(scores).hasSize(2).allMatch(Double::isFinite);
            assertThat(scores.getFirst()).isGreaterThan(scores.getLast());
            assertThat(service.metadata().sha256())
                    .isEqualTo("c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9");
        } finally {
            service.close();
        }
    }

    private static ServiceHandle service(
            int batchSize,
            Duration timeout,
            OnnxCrossEncoderScoringService.BatchScorer scorer) {
        return new ServiceHandle(new OnnxCrossEncoderScoringService(
                METADATA,
                batchSize,
                timeout,
                scorer));
    }

    private static OnnxCrossEncoderScoringService productionService(Path modelPath) {
        return new OnnxCrossEncoderScoringService(
                modelPath.toString(),
                "cross-encoder/test-model",
                "0123456789abcdef0123456789abcdef01234567",
                MODEL_SHA,
                "b".repeat(64),
                16,
                512,
                8,
                TimeUnit.SECONDS.toMillis(5));
    }

    private record ServiceHandle(OnnxCrossEncoderScoringService service) implements AutoCloseable {

        @Override
        public void close() {
            service.close();
        }
    }
}
