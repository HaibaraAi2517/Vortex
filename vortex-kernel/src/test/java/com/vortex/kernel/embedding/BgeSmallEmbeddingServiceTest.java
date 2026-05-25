package com.vortex.kernel.embedding;

import com.vortex.common.exception.EmbeddingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BgeSmallEmbeddingServiceTest {

    @Test
    void embed_whenPredictReturnsZeroVector_throwsEmbeddingException() {
        TestableEmbeddingService service = new TestableEmbeddingService(
                List.of(zeroVector()),
                List.of());

        assertThatThrownBy(() -> service.embed("zero-vector"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("zero vector");
    }

    @Test
    void embedBatch_whenBatchContainsZeroVector_throwsEmbeddingException() {
        TestableEmbeddingService service = new TestableEmbeddingService(
                List.of(),
                List.of(List.of(nonZeroVector(), zeroVector())));

        assertThatThrownBy(() -> service.embedBatch(List.of("ok", "bad")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("zero vector");
    }

    private static float[] zeroVector() {
        return new float[512];
    }

    private static float[] nonZeroVector() {
        float[] vector = new float[512];
        vector[0] = 1.0f;
        return vector;
    }

    private static final class TestableEmbeddingService extends BgeSmallEmbeddingService {
        private final java.util.ArrayDeque<float[]> singleResponses;
        private final java.util.ArrayDeque<List<float[]>> batchResponses;

        private TestableEmbeddingService(List<float[]> singleResponses, List<List<float[]>> batchResponses) {
            super("unused");
            this.singleResponses = new java.util.ArrayDeque<>(singleResponses);
            this.batchResponses = new java.util.ArrayDeque<>(batchResponses);
        }

        @Override
        public void init() {
            // Disabled for unit tests.
        }

        @Override
        float[] predictSingle(String input) {
            return singleResponses.removeFirst();
        }

        @Override
        List<float[]> predictBatch(List<String> texts) {
            return batchResponses.removeFirst();
        }
    }
}
