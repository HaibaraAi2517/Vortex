package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.TokenCounter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Splits raw text into semantically coherent chunks that respect token budgets.
 *
 * Strategy (rule-based MVP):
 *   1. Split on paragraph boundaries (blank lines) first.
 *   2. If a paragraph exceeds maxTokensPerChunk, split further on sentence
 *      boundaries (。！？.!?).
 *   3. Token count is measured by the configured tokenizer, so chunking matches
 *      the embedding path's real token accounting.
 */
@Component
public class SemanticTextSplitter {

    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n{2,}");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？.!?])\\s*");

    private final TokenCounter tokenCounter;
    private final int maxTokensPerChunk;

    public SemanticTextSplitter(
            @Qualifier("bgeSmallEmbeddingService") TokenCounter tokenCounter,
            @Value("${vortex.kernel.splitter.max-tokens-per-chunk:512}") int maxTokensPerChunk) {
        this.tokenCounter = tokenCounter;
        this.maxTokensPerChunk = maxTokensPerChunk;
    }

    /**
     * Split {@code text} into fragments.
     *
     * @param text      raw input text
     * @param namespace agent/session namespace
     * @param tags      optional tags applied to all resulting fragments
     */
    public List<MemoryFragment> split(
            String text,
            String namespace,
            List<String> tags,
            String reasoningChainId,
            Long pinTtlMillis) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<MemoryFragment> result = new ArrayList<>();
        String[] paragraphs = PARAGRAPH_BOUNDARY.split(text.strip());
        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;
            int tokens = countTokens(trimmed);
            if (tokens <= maxTokensPerChunk) {
                result.add(buildFragment(trimmed, namespace, tags, reasoningChainId, pinTtlMillis));
            } else {
                // Split paragraph into sentences
                String[] sentences = SENTENCE_BOUNDARY.split(trimmed);
                StringBuilder buf = new StringBuilder();
                int bufTokens = 0;
                for (String sentence : sentences) {
                    if (countTokens(sentence) > maxTokensPerChunk) {
                        if (buf.length() > 0) {
                            result.add(buildFragment(buf.toString().strip(), namespace, tags, reasoningChainId, pinTtlMillis));
                            buf.setLength(0);
                            bufTokens = 0;
                        }
                        for (String part : splitOversizedSentence(sentence)) {
                            result.add(buildFragment(part, namespace, tags, reasoningChainId, pinTtlMillis));
                        }
                        continue;
                    }
                    int st = countTokens(sentence);
                    if (bufTokens + st > maxTokensPerChunk && buf.length() > 0) {
                        result.add(buildFragment(buf.toString().strip(), namespace, tags, reasoningChainId, pinTtlMillis));
                        buf.setLength(0);
                        bufTokens = 0;
                    }
                    buf.append(sentence).append(" ");
                    bufTokens += st;
                }
                if (buf.length() > 0) {
                    result.add(buildFragment(buf.toString().strip(), namespace, tags, reasoningChainId, pinTtlMillis));
                }
            }
        }
        return result;
    }

    /** Long unpunctuated text must not become one silently truncated embedding. */
    private List<String> splitOversizedSentence(String sentence) {
        List<String> parts = new ArrayList<>();
        String remaining = sentence.strip();
        while (!remaining.isEmpty() && countTokens(remaining) > maxTokensPerChunk) {
            int low = 1;
            int high = remaining.codePointCount(0, remaining.length());
            int fittingEnd = 0;
            while (low <= high) {
                int middle = low + (high - low) / 2;
                int end = remaining.offsetByCodePoints(0, middle);
                if (countTokens(remaining.substring(0, end)) <= maxTokensPerChunk) {
                    fittingEnd = end;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            if (fittingEnd == 0) throw new IllegalArgumentException("Chunk token budget cannot fit a single code point");
            String part = remaining.substring(0, fittingEnd).strip();
            if (!part.isEmpty()) parts.add(part);
            remaining = remaining.substring(fittingEnd).strip();
        }
        if (!remaining.isEmpty()) parts.add(remaining);
        return parts;
    }

    private MemoryFragment buildFragment(
            String content,
            String namespace,
            List<String> tags,
            String reasoningChainId,
            Long pinTtlMillis) {
        MemoryFragment fragment = MemoryFragment.builder()
                .id(UUID.randomUUID().toString())
                .namespace(namespace)
                .content(content)
                .tokenCount(countTokens(content))
                .tags(tags == null ? List.of() : List.copyOf(tags))
                .reasoningChainId(reasoningChainId)
                .build();
        if (pinTtlMillis != null) {
            fragment.pinForMillis(pinTtlMillis);
        }
        return fragment;
    }

    public int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, tokenCounter.countTokens(text));
    }
}
