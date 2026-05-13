package com.vortex.kernel.paging;

import com.vortex.common.model.PageState;
import com.vortex.common.model.SemanticPage;
import com.vortex.kernel.hmc.AdaptiveWeightProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Page-level eviction policy extending the Semantic-LRU concept to pages.
 *
 * Score = w1 * recencyScore(page.lastAccessTime)
 *       + w2 * avgSimilarity(page.centroid, queryEmbedding)
 *       + w3 * dagDistance(page, currentNodeId)
 *       + w4 * semanticDistance(page.centroid, queryEmbedding)
 *
 * Pages with the LOWEST score are evicted first.
 */
@Slf4j
@Component
public class PageEvictionPolicy {

    private static final double ONE_DAY_MILLIS = 86_400_000L;

    private final double w1Recency;
    private final double w2Similarity;
    private final double w3DagDistance;
    private final double w4SemanticDistance;

    public PageEvictionPolicy(
            @Value("${vortex.kernel.paging.eviction.recency-weight:0.25}") double w1Recency,
            @Value("${vortex.kernel.paging.eviction.similarity-weight:0.25}") double w2Similarity,
            @Value("${vortex.kernel.paging.eviction.dag-distance-weight:0.25}") double w3DagDistance,
            @Value("${vortex.kernel.paging.eviction.semantic-distance-weight:0.25}") double w4SemanticDistance) {
        this.w1Recency = w1Recency;
        this.w2Similarity = w2Similarity;
        this.w3DagDistance = w3DagDistance;
        this.w4SemanticDistance = w4SemanticDistance;
    }

    /**
     * Compute a composite score for a page.
     * Higher score = more valuable, less likely to be evicted.
     */
    public double scorePage(SemanticPage page, String currentNodeId, float[] queryEmbedding) {
        double recency = recencyScore(page.getLastAccessTime());
        double similarity = centroidSimilarity(page, queryEmbedding);
        double dagDist = dagDistance(page, currentNodeId);
        double semDist = 1.0 - similarity; // semantic distance = 1 - cosine similarity

        return w1Recency * recency
                + w2Similarity * similarity
                + w3DagDistance * (1.0 - dagDist)  // invert: low DAG distance → high score
                + w4SemanticDistance * similarity;  // use similarity, not distance, for scoring
    }

    /**
     * Compute score using an AdaptiveWeightProfile for the semantic-LRU components
     * and separate DAG/semantic weights.
     */
    public double scorePage(SemanticPage page, String currentNodeId, float[] queryEmbedding,
                            AdaptiveWeightProfile profile) {
        if (profile == null) {
            return scorePage(page, currentNodeId, queryEmbedding);
        }
        double recency = recencyScore(page.getLastAccessTime());
        double similarity = centroidSimilarity(page, queryEmbedding);
        double dagDist = dagDistance(page, currentNodeId);

        return profile.getAlpha() * recency
                + profile.getBeta() * similarity
                + profile.getGamma() * (1.0 - dagDist);
    }

    /**
     * DAG distance between a page and the current DAG node.
     * <ul>
     *   <li>0.0 — page is directly associated with currentNodeId</li>
     *   <li>0.3 — page's nodes can reach currentNodeId in 1 hop</li>
     *   <li>0.6 — page's nodes can reach currentNodeId in 2 hops</li>
     *   <li>1.0 — no relationship</li>
     * </ul>
     */
    public double dagDistance(SemanticPage page, String nodeId) {
        if (nodeId == null || page.getDagNodeIds().isEmpty()) {
            return 1.0;
        }
        if (page.getDagNodeIds().contains(nodeId)) {
            return 0.0;
        }
        // For MVP, we approximate DAG distance using the page's node associations.
        // A full implementation would do BFS on the DAG graph; here we use
        // the presence of any associated node as a heuristic for 1-hop proximity.
        // If any page dagNodeId shares a prefix (same task), treat as 1-hop.
        for (String pageNodeId : page.getDagNodeIds()) {
            if (pageNodeId != null && nodeId != null
                    && pageNodeId.length() > 8 && nodeId.length() > 8
                    && pageNodeId.substring(0, 8).equals(nodeId.substring(0, 8))) {
                return 0.3;
            }
        }
        return 1.0;
    }

    /**
     * Select pages for eviction to free at least {@code targetTokens} worth of capacity.
     * Pages are sorted from lowest score (evict first) to highest.
     *
     * @param pages        candidate pages (typically RESIDENT pages)
     * @param targetTokens approximate token count to free
     * @return pages to evict, sorted by ascending score
     */
    public List<SemanticPage> selectPagesForEviction(
            Collection<SemanticPage> pages,
            long targetTokens,
            String currentNodeId,
            float[] queryEmbedding) {

        if (pages.isEmpty()) return List.of();

        // Score all pages
        List<ScoredPage> scored = pages.stream()
                .filter(p -> p.getState() == PageState.RESIDENT)
                .map(p -> new ScoredPage(p, scorePage(p, currentNodeId, queryEmbedding)))
                .sorted(Comparator.comparingDouble(ScoredPage::score))
                .toList();

        // Select lowest-scoring pages until target tokens is reached
        List<SemanticPage> toEvict = new ArrayList<>();
        long estimatedTokens = 0;
        for (ScoredPage sp : scored) {
            toEvict.add(sp.page());
            // Rough estimate: each page has ~10 fragments, average ~50 tokens each
            estimatedTokens += sp.page().getFragmentIds().size() * 50L;
            if (estimatedTokens >= targetTokens && toEvict.size() >= 1) {
                break;
            }
        }
        return toEvict;
    }

    // ---- Scoring components ----

    /** Normalised recency: 1.0 = just accessed, decays logarithmically. */
    private static double recencyScore(long lastAccessTime) {
        long ageMs = Math.max(0L, System.currentTimeMillis() - lastAccessTime);
        double ageDays = (double) ageMs / ONE_DAY_MILLIS;
        return 1.0 / (1.0 + Math.log1p(ageDays));
    }

    /** Cosine similarity between page centroid and query embedding. */
    private static double centroidSimilarity(SemanticPage page, float[] queryEmbedding) {
        float[] centroid = page.getCentroid();
        if (centroid == null || queryEmbedding == null || centroid.length != queryEmbedding.length) {
            return 0.0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < centroid.length; i++) {
            dot += (double) centroid[i] * queryEmbedding[i];
            normA += (double) centroid[i] * centroid[i];
            normB += (double) queryEmbedding[i] * queryEmbedding[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    private record ScoredPage(SemanticPage page, double score) {}
}
