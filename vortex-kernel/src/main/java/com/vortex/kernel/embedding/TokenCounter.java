package com.vortex.kernel.embedding;

/**
 * Counts tokens with the same tokenizer family used by the embedding pipeline.
 */
public interface TokenCounter {

    /**
     * Count tokens for the provided text.
     *
     * @param text raw text
     * @return token count
     */
    int countTokens(String text);
}

