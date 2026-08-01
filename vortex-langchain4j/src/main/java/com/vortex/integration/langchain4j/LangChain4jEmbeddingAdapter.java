package com.vortex.integration.langchain4j;

import com.vortex.common.exception.EmbeddingException;
import com.vortex.kernel.embedding.EmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adapts a LangChain4j {@link EmbeddingModel} to Vortex's embedding contract. */
public final class LangChain4jEmbeddingAdapter implements EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final int dimension;

    public LangChain4jEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this(embeddingModel, resolveDimension(embeddingModel));
    }

    public LangChain4jEmbeddingAdapter(EmbeddingModel embeddingModel, int dimension) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be greater than zero");
        }
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        requireText(text);
        try {
            return toVector(embeddingModel.embed(text), "single embedding");
        } catch (EmbeddingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EmbeddingException("LangChain4j embedding failed: " + safeMessage(e), e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) {
            return List.of();
        }
        texts.forEach(LangChain4jEmbeddingAdapter::requireText);

        try {
            List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            if (response == null || response.content() == null || response.content().size() != texts.size()) {
                throw new EmbeddingException("LangChain4j batch embedding returned unexpected cardinality");
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (Embedding embedding : response.content()) {
                vectors.add(toVector(embedding, "batch embedding"));
            }
            return List.copyOf(vectors);
        } catch (EmbeddingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EmbeddingException("LangChain4j batch embedding failed: " + safeMessage(e), e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private float[] toVector(Response<Embedding> response, String operation) {
        if (response == null) {
            throw new EmbeddingException("LangChain4j " + operation + " returned no response");
        }
        return toVector(response.content(), operation);
    }

    private float[] toVector(Embedding embedding, String operation) {
        if (embedding == null || embedding.vector() == null) {
            throw new EmbeddingException("LangChain4j " + operation + " returned no vector");
        }
        float[] vector = embedding.vector();
        if (vector.length != dimension) {
            throw new EmbeddingException(
                    "LangChain4j " + operation + " dimension mismatch: expected "
                            + dimension + " but got " + vector.length);
        }
        return vector.clone();
    }

    private static int resolveDimension(EmbeddingModel embeddingModel) {
        return Objects.requireNonNull(embeddingModel, "embeddingModel").dimension();
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text must not be blank");
        }
    }

    private static String safeMessage(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
