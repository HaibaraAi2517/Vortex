package com.vortex.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * A single unit of memory stored in the HMC.
 * Represents a semantically coherent text fragment with its embedding vector.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryFragment {

    private static final long ONE_DAY_MILLIS = 86_400_000L;
    private static final double RECALL_IMPORTANCE_GAIN = 0.15;
    private static final double REDUNDANCY_THRESHOLD = 0.95;
    private static final double MAX_REDUNDANCY_PENALTY = 0.25;
    private static final double MAX_NOVELTY_BONUS = 0.15;

    /** Unique identifier (UUID). */
    private String id;

    /** Namespace / agent session this fragment belongs to. */
    private String namespace;

    /** Raw text content. */
    private String content;

    /**
     * L1 embedding vector — produced by BGE-Small (512-dim).
     * Used for fast in-process cosine scoring in L1 (Caffeine).
     * Null until the fragment has been embedded.
     */
    private float[] embedding;

    /**
     * L2 embedding vector — produced by the cloud model (DeepSeek, 1024-dim).
     * Used for Milvus upsert and semantic search in L2.
     * Null when cloud embedding is disabled; MilvusWarmStore falls back to {@link #embedding}.
     */
    private float[] l2Embedding;

    /** Number of tokens in {@code content} (BPE-based count). */
    private int tokenCount;

    /** Semantic importance score [0.0, 1.0] — higher means more important. */
    @Builder.Default
    private double importance = 0.5;

    /** Epoch millis of last access (used for recency scoring). */
    @Builder.Default
    private long lastAccessTime = System.currentTimeMillis();

    /** Creation timestamp. */
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Optional tags for filtering (e.g., "task:coding", "role:user"). */
    private List<String> tags;

    /** Optional reasoning chain/group identifier used for dependency-safe retention. */
    private String reasoningChainId;

    /** Epoch millis until which the fragment is pinned and not eligible for eviction. */
    private Long pinnedUntil;

    /**
     * Composite Semantic-LRU score used by the eviction policy.
     * Score = α * recencyScore + β * semanticSimilarity + γ * importance
     * Higher score = more likely to stay in L1.
     */
    public double computeEvictionScore(
            float[] queryEmbedding,
            double alpha,
            double beta,
            double gamma,
            double redundancyPenalty,
            double noveltyBonus) {
        return describeEvictionScore(queryEmbedding, alpha, beta, gamma, redundancyPenalty, noveltyBonus).totalScore();
    }

    public EvictionScoreBreakdown describeEvictionScore(
            float[] queryEmbedding,
            double alpha,
            double beta,
            double gamma,
            double redundancyPenalty,
            double noveltyBonus) {
        double recency = recencyScore();
        double similarity = similarityTo(queryEmbedding);
        double recencyContribution = alpha * recency;
        double similarityContribution = beta * similarity;
        double importanceContribution = gamma * importance;
        double boundedPenalty = clamp(redundancyPenalty, 0.0, MAX_REDUNDANCY_PENALTY);
        double boundedNovelty = clamp(noveltyBonus, 0.0, MAX_NOVELTY_BONUS);
        return new EvictionScoreBreakdown(
                recency,
                similarity,
                importance,
                recencyContribution,
                similarityContribution,
                importanceContribution,
                boundedPenalty,
                boundedNovelty,
                recencyContribution + similarityContribution + importanceContribution - boundedPenalty + boundedNovelty
        );
    }

    /** Normalised recency: 1.0 = just accessed, then decays logarithmically over time. */
    private double recencyScore() {
        long ageMs = Math.max(0L, System.currentTimeMillis() - lastAccessTime);
        double ageDays = (double) ageMs / ONE_DAY_MILLIS;
        return 1.0 / (1.0 + Math.log1p(ageDays));
    }

    public void recordAccess() {
        lastAccessTime = System.currentTimeMillis();
    }

    public void reinforceImportanceOnRecall() {
        importance = Math.min(1.0, importance + (1.0 - importance) * RECALL_IMPORTANCE_GAIN);
    }

    public void pinForMillis(long ttlMillis) {
        if (ttlMillis <= 0) {
            return;
        }
        pinnedUntil = System.currentTimeMillis() + ttlMillis;
    }

    public void unpin() {
        pinnedUntil = null;
    }

    public boolean clearExpiredPin() {
        if (pinnedUntil != null && pinnedUntil <= System.currentTimeMillis()) {
            pinnedUntil = null;
            return true;
        }
        return false;
    }

    @JsonIgnore
    public boolean isPinned() {
        return pinnedUntil != null && pinnedUntil > System.currentTimeMillis();
    }

    public double redundancySimilarityTo(MemoryFragment other) {
        if (other == null || other == this) {
            return 0.0;
        }
        double l1Similarity = cosineSimilarityNullable(embedding, other.getEmbedding());
        double l2Similarity = cosineSimilarityNullable(l2Embedding, other.getL2Embedding());
        return Math.max(l1Similarity, l2Similarity);
    }

    public double redundancyPenaltyAgainst(MemoryFragment other) {
        double similarity = redundancySimilarityTo(other);
        if (similarity < REDUNDANCY_THRESHOLD) {
            return 0.0;
        }
        return Math.min(MAX_REDUNDANCY_PENALTY, similarity - REDUNDANCY_THRESHOLD);
    }

    public double noveltyBonusAgainst(MemoryFragment other) {
        double similarity = redundancySimilarityTo(other);
        return Math.min(MAX_NOVELTY_BONUS, Math.max(0.0, 1.0 - similarity) * MAX_NOVELTY_BONUS);
    }

    public double similarityTo(float[] queryEmbedding) {
        return (queryEmbedding != null && embedding != null)
                ? cosineSimilarity(embedding, queryEmbedding)
                : 0.0;
    }

    public record EvictionScoreBreakdown(
            double recencyScore,
            double similarityScore,
            double importanceScore,
            double recencyContribution,
            double similarityContribution,
            double importanceContribution,
            double redundancyPenalty,
            double noveltyBonus,
            double totalScore) {
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double cosineSimilarityNullable(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        return cosineSimilarity(a, b);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
