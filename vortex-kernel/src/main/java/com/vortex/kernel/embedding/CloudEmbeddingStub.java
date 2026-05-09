package com.vortex.kernel.embedding;

import lombok.extern.slf4j.Slf4j;

/**
 * @deprecated Replaced by {@link DeepSeekEmbeddingService}.
 * Kept for reference only — not registered as a Spring bean.
 */
@Slf4j
@Deprecated
public class CloudEmbeddingStub implements EmbeddingService {

    // ZhipuAI embedding-2 outputs 1024-dim; DeepSeek outputs 1024-dim; OpenAI text-embedding-3-small outputs 1536-dim.
    // Update this constant when you wire a real provider.
    private static final int DIMENSION = 1024;

    @Override
    public float[] embed(String text) {
        // TODO: implement HTTP call to cloud embedding API
        // Example for ZhipuAI:
        //   POST https://open.bigmodel.cn/api/paas/v4/embeddings
        //   { "model": "embedding-2", "input": text }
        log.warn("CloudEmbeddingStub.embed() called — no API key configured. Returning zero vector.");
        return new float[DIMENSION];
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }
}
