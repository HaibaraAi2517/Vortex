package com.vortex.integration.langchain4j;

import com.vortex.common.exception.EmbeddingException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jEmbeddingAdapterTest {

    @Test
    void adaptsSingleAndBatchEmbeddingCalls() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4jEmbeddingAdapter adapter = new LangChain4jEmbeddingAdapter(model);

        float[] single = adapter.embed("single");
        List<float[]> batch = adapter.embedBatch(List.of("first", "second"));

        assertThat(single).containsExactly(1.0f, 0.0f, 0.0f);
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0)).containsExactly(1.0f, 0.0f, 0.0f);
        assertThat(batch.get(1)).containsExactly(0.0f, 1.0f, 0.0f);
        assertThat(adapter.dimension()).isEqualTo(3);
        assertThat(model.singleCalls).hasValue(1);
        assertThat(model.batchCalls).hasValue(1);
    }

    @Test
    void rejectsUnexpectedEmbeddingDimension() {
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                return Response.from(Embedding.from(new float[]{1.0f, 0.0f}));
            }

            @Override
            public int dimension() {
                return 3;
            }
        };
        LangChain4jEmbeddingAdapter adapter = new LangChain4jEmbeddingAdapter(model);

        assertThatThrownBy(() -> adapter.embed("mismatch"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("expected 3 but got 2");
    }

    @Test
    void rejectsBlankTextBeforeCallingModel() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4jEmbeddingAdapter adapter = new LangChain4jEmbeddingAdapter(model);

        assertThatThrownBy(() -> adapter.embed(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThat(model.singleCalls).hasValue(0);
    }

    private static final class RecordingEmbeddingModel implements EmbeddingModel {
        private final AtomicInteger singleCalls = new AtomicInteger();
        private final AtomicInteger batchCalls = new AtomicInteger();

        @Override
        public Response<Embedding> embed(String text) {
            singleCalls.incrementAndGet();
            return Response.from(Embedding.from(new float[]{1.0f, 0.0f, 0.0f}));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            batchCalls.incrementAndGet();
            return Response.from(List.of(
                    Embedding.from(new float[]{1.0f, 0.0f, 0.0f}),
                    Embedding.from(new float[]{0.0f, 1.0f, 0.0f})));
        }

        @Override
        public int dimension() {
            return 3;
        }
    }
}
