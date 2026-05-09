package com.vortex.kernel.embedding;

import java.util.List;

/**
 * Converts text into a dense float vector (embedding).
 *
 * Two implementations exist:
 *   - {@link BgeSmallEmbeddingService}  — local ONNX, ~5-15 ms, 512-dim, no API key needed
 *   - {@code CloudEmbeddingService}     — cloud API (stub until API key is available)
 *
 * The HMC uses the local implementation for L1 operations and will delegate
 * to the cloud implementation for L2 once a key is configured.
 */
public interface EmbeddingService {

    /**
     * Embed a single text string.
     *
     * @param text input text (should be a single semantic chunk, not a full document)
     * @return normalised float vector
     */
    float[] embed(String text);

    /**
     * Batch embed multiple texts.
     * Default implementation calls {@link #embed(String)} sequentially;
     * implementations may override for true batching.
     */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** Dimensionality of the vectors produced by this service. */
    int dimension();
}
