package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.storage.api.FactMemoryStore;
import com.vortex.storage.api.MemoryFact;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Explicit facts avoid guessing whether free-form utterances refer to the same subject. */
@Service
@ConditionalOnProperty(name = "vortex.storage.backend", havingValue = "local")
public class FactMemoryService {
    private final FactMemoryStore store;
    private final EmbeddingService embeddings;
    private final TokenCounter tokens;

    public FactMemoryService(FactMemoryStore store,
            @Qualifier("bgeSmallEmbeddingService") EmbeddingService embeddings,
            @Qualifier("bgeSmallEmbeddingService") TokenCounter tokens) {
        this.store = store;
        this.embeddings = embeddings;
        this.tokens = tokens;
    }

    public MemoryFact put(String namespace, String key, String content, List<String> tags, long expectedRevision, Instant expiresAt) {
        int count = tokens.countTokens(content);
        // One fact is one retrievable unit; reject silent embedding truncation.
        if (count > 480) throw new IllegalArgumentException("A fact must fit in 480 tokens; split it into more specific keys");
        MemoryFragment fragment = MemoryFragment.builder().namespace(namespace).content(content)
                .tags(tags == null ? List.of() : List.copyOf(tags)).tokenCount(count)
                .embedding(embeddings.embed(content)).build();
        return store.putFact(key, fragment, expectedRevision, expiresAt);
    }
}
