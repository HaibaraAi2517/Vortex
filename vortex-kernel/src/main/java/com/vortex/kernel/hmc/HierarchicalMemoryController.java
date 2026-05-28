package com.vortex.kernel.hmc;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.exception.EmbeddingException;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.SemanticPage;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.paging.SemanticPagingManager;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L1HotStoreAdmin;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hierarchical Memory Controller (HMC) — facade.
 *
 * Orchestrates the three-tier memory pipeline (L1 Caffeine → L2 Milvus → L3 MinIO)
 * by delegating to specialized components:
 *   {@link TieredEvictionCoordinator} — eviction, admission, quota, tier indexing
 *   {@link FragmentPinManager} — pin lifecycle
 *   {@link RecallOrchestrator} — semantic recall pipeline
 *   {@link MemoryDiagnosticsCollector} — health diagnostics snapshots
 *   {@link RedundancyAnalyzer} — redundancy/novelty computation
 */
@Slf4j
@Service
public class HierarchicalMemoryController {

    private final L1HotStore l1;
    private final L2WarmStore l2;
    private final L3ColdStore l3;
    private final SemanticEvictionPolicy evictionPolicy;
    private final AdaptiveWeightLearner adaptiveWeightLearner;
    private final EvictionDecisionLogger evictionDecisionLogger;
    private final EvictionRegretTracker regretTracker;
    private final MemorySloTracker sloTracker;
    private final FragmentPersistenceManager persistenceManager;
    private final SemanticTextSplitter splitter;

    /** BGE-Small: always available, used for L1 fast scoring. */
    private final EmbeddingService l1EmbeddingService;

    /**
     * Cloud embedding (DeepSeek): optional, used for L2 Milvus upsert/search.
     * Null when vortex.kernel.embedding.cloud.enabled=false.
     * Falls back to l1EmbeddingService when null.
     */
    private final EmbeddingService l2EmbeddingService;

    /** Semantic paging — optional, graceful no-op when not configured. */
    private final SemanticPagingManager pagingManager;

    private final TieredEvictionCoordinator evictionCoordinator;
    private final FragmentPinManager pinManager;
    private final RecallOrchestrator recallOrchestrator;
    private final MemoryDiagnosticsCollector diagnosticsCollector;

    @Autowired
    public HierarchicalMemoryController(
            L1HotStore l1,
            L2WarmStore l2,
            L3ColdStore l3,
            SemanticEvictionPolicy evictionPolicy,
            NamespaceQuotaManager namespaceQuotaManager,
            AdaptiveWeightLearner adaptiveWeightLearner,
            EvictionDecisionLogger evictionDecisionLogger,
            EvictionRegretTracker regretTracker,
            MemorySloTracker sloTracker,
            FragmentPersistenceManager persistenceManager,
            SemanticTextSplitter splitter,
            @Qualifier("bgeSmallEmbeddingService") EmbeddingService l1EmbeddingService,
            @Qualifier("cloudEmbeddingService") ObjectProvider<EmbeddingService> cloudEmbeddingProvider,
            ObjectProvider<SemanticPagingManager> pagingManagerProvider,
            @Value("${vortex.kernel.eviction.threshold:0.85}") double evictionThreshold,
            TieredEvictionCoordinator evictionCoordinator,
            FragmentPinManager pinManager,
            RecallOrchestrator recallOrchestrator,
            MemoryDiagnosticsCollector diagnosticsCollector) {
        this.l1 = l1;
        this.l2 = l2;
        this.l3 = l3;
        this.evictionPolicy = evictionPolicy;
        this.adaptiveWeightLearner = adaptiveWeightLearner;
        this.evictionDecisionLogger = evictionDecisionLogger;
        this.regretTracker = regretTracker;
        this.sloTracker = sloTracker;
        this.persistenceManager = persistenceManager;
        this.splitter = splitter;
        this.l1EmbeddingService = l1EmbeddingService;
        this.l2EmbeddingService = cloudEmbeddingProvider.getIfAvailable();
        this.pagingManager = pagingManagerProvider.getIfAvailable();
        this.evictionCoordinator = evictionCoordinator;
        this.pinManager = pinManager;
        this.recallOrchestrator = recallOrchestrator;
        this.diagnosticsCollector = diagnosticsCollector;

        if (l1 instanceof L1HotStoreAdmin admin) {
            admin.registerEvictionListener(this::handleL1Eviction);
        }

        if (this.l2EmbeddingService != null) {
            log.info("HMC initialized: L1=BGE-Small({}d), L2=DeepSeek({}d){}",
                    l1EmbeddingService.dimension(), l2EmbeddingService.dimension(),
                    pagingManager != null ? ", paging=enabled" : "");
        } else {
            log.info("HMC initialized: L1=L2=BGE-Small({}d) [cloud embedding disabled]{}",
                    l1EmbeddingService.dimension(),
                    pagingManager != null ? ", paging=enabled" : "");
        }

        validateL2DimensionCompatibility();
    }

    // ---- Public API ----

    /**
     * Store a raw text fragment.
     * The text is split at semantic boundaries, each chunk stored in L1.
     * Async propagation to L2/L3 happens in the background.
     */
    public List<String> store(
            String content,
            String namespace,
            List<String> tags,
            String reasoningChainId,
            Long pinTtlMillis) {
        List<MemoryFragment> chunks = splitter.split(content, namespace, tags, reasoningChainId, pinTtlMillis);
        List<String> ids = new ArrayList<>();
        for (MemoryFragment chunk : chunks) {
            storeFragment(chunk);
            ids.add(chunk.getId());
        }
        return ids;
    }

    /**
     * Store a pre-built fragment.
     * Generates L1 embedding (BGE-Small) synchronously so eviction scoring works immediately.
     * When cloud embedding is enabled, also generates L2 embedding (DeepSeek) for Milvus.
     */
    public void storeFragment(MemoryFragment fragment) {
        long startedAt = System.nanoTime();
        ensureL1Embedding(fragment);
        populateOptionalL2Embedding(fragment);
        evictionCoordinator.admitToL1(fragment, "initial-store");
        persistenceManager.persistAsync(fragment, "initial-store");
        sloTracker.recordStoreLatency(System.nanoTime() - startedAt);
    }

    /** Delegate to {@link RecallOrchestrator#recall}. */
    public RecallResult recall(RecallQuery query) {
        return recallOrchestrator.recall(query);
    }

    public void recordFeedback(MemoryFeedbackRequest feedbackRequest) {
        AdaptiveWeightLearner.LearningSnapshot snapshot = adaptiveWeightLearner.recordFeedback(
                feedbackRequest.getRecallSessionId(),
                feedbackRequest.getUsedFragmentIds() == null
                        ? Set.of()
                        : new HashSet<>(feedbackRequest.getUsedFragmentIds()),
                feedbackRequest.isAnswerAccepted(),
                regretTracker.snapshot().regretRate());
        if (snapshot != null) {
            sloTracker.recordLearningLift(
                    snapshot.shadowEvaluation().relativeLift(),
                    snapshot.shadowEvaluation().baselineRelativeLift(),
                    snapshot.shadowEvaluation().baselineWinRate());
        }
    }

    public AdaptiveWeightLearner.LearningSnapshot learningSnapshot(MemoryScenario scenario) {
        return adaptiveWeightLearner.snapshot(scenario);
    }

    public MemorySloTracker.SloSnapshot sloSnapshot() {
        return sloTracker.snapshot();
    }

    /** Delegate to {@link MemoryDiagnosticsCollector#diagnosticsSnapshot}. */
    public MemoryDiagnosticsCollector.MemoryDiagnosticsSnapshot diagnosticsSnapshot() {
        return diagnosticsCollector.diagnosticsSnapshot();
    }

    /** Delegate to {@link FragmentPinManager#pinFragment}. */
    public Optional<MemoryFragment> pinFragment(String fragmentId, long ttlMillis) {
        return pinManager.pinFragment(fragmentId, ttlMillis);
    }

    public Optional<MemoryFragment> getFragment(String fragmentId) {
        return findFragment(fragmentId);
    }

    public boolean deleteFragment(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) {
            return false;
        }
        Optional<MemoryFragment> existing = findFragment(fragmentId);
        if (existing.isEmpty()) {
            return false;
        }
        MemoryFragment fragment = existing.get();
        l1.remove(fragmentId);
        pinManager.removePinIndex(fragment);
        evictionCoordinator.removeFromTierIndexes(fragment);
        l2.delete(fragmentId);
        l3.deleteFragment(fragmentId);
        return true;
    }

    /** Delegate to {@link FragmentPinManager#unpinFragment}. */
    public Optional<MemoryFragment> unpinFragment(String fragmentId) {
        return pinManager.unpinFragment(fragmentId);
    }

    /** Delegate to {@link FragmentPinManager#clearExpiredPins} (also called by @Scheduled). */
    public void clearExpiredPins() {
        pinManager.clearExpiredPins();
    }

    /** Delegate to {@link TieredEvictionCoordinator#maybeEvict}. */
    public void maybeEvict(String namespace, float[] queryEmbedding) {
        evictionCoordinator.maybeEvict(namespace, queryEmbedding);
    }

    /** Delegate to {@link TieredEvictionCoordinator#admitPage}. */
    public void admitPage(SemanticPage page, List<MemoryFragment> fragments) {
        evictionCoordinator.admitPage(page, fragments);
    }

    /** Delegate to {@link TieredEvictionCoordinator#admitPage}. */
    public void admitPage(SemanticPage page, List<MemoryFragment> fragments, String primaryFragmentId) {
        evictionCoordinator.admitPage(page, fragments, primaryFragmentId);
    }

    /** Delegate to {@link TieredEvictionCoordinator#rebalanceTierIndexes}. */
    public void rebalanceTierIndexes() {
        evictionCoordinator.rebalanceTierIndexes();
    }

    /** Expose L1 store for monitoring (e.g., health endpoints). */
    public L1HotStore getL1() {
        return l1;
    }

    // ---- Internal helpers ----

    /**
     * Three-tier fragment lookup: L1 → L3 → L2.
     */
    Optional<MemoryFragment> findFragment(String fragmentId) {
        Optional<MemoryFragment> l1Fragment = l1.peek(fragmentId);
        if (l1Fragment.isPresent()) {
            return l1Fragment;
        }
        Optional<MemoryFragment> archived = l3.retrieveFragment(fragmentId);
        if (archived.isPresent()) {
            MemoryFragment fragment = archived.get();
            fragment.clearExpiredPin();
            ensureL1Embedding(fragment);
            populateOptionalL2Embedding(fragment);
            return Optional.of(fragment);
        }
        return l2.get(fragmentId).map(fragment -> {
            ensureL1Embedding(fragment);
            return fragment;
        });
    }

    boolean matchesAllTags(MemoryFragment fragment, List<String> requiredTags) {
        if (requiredTags.isEmpty()) {
            return true;
        }
        List<String> fragmentTags = fragment.getTags();
        if (fragmentTags == null || fragmentTags.isEmpty()) {
            return false;
        }
        Set<String> tagSet = fragmentTags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .collect(Collectors.toSet());
        return requiredTags.stream().allMatch(tagSet::contains);
    }

    List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
    }

    private void handleL1Eviction(MemoryFragment fragment, L1HotStoreAdmin.EvictionCause cause) {
        SemanticEvictionPolicy.EvictionCandidate candidate = evictionPolicy.scoreFragment(fragment, null);
        evictionDecisionLogger.logFallbackEviction(candidate, fragment.getNamespace(), cause.name());
        regretTracker.recordEviction(fragment, "caffeine-" + cause.name().toLowerCase(Locale.ROOT));
        persistenceManager.persistAsync(fragment, "caffeine-" + cause.name().toLowerCase(Locale.ROOT));
    }

    // ---- Embedding helpers ----

    void ensureL1Embedding(MemoryFragment fragment) {
        if (fragment.getEmbedding() == null) {
            fragment.setEmbedding(requireEmbedding(l1EmbeddingService, fragment.getContent(),
                    "L1 fragment " + fragment.getId()));
        }
    }

    void populateOptionalL2Embedding(MemoryFragment fragment) {
        if (l2EmbeddingService == null || fragment.getL2Embedding() != null) {
            return;
        }
        try {
            fragment.setL2Embedding(requireEmbedding(l2EmbeddingService, fragment.getContent(),
                    "L2 fragment " + fragment.getId()));
        } catch (EmbeddingException e) {
            log.warn("L2 embedding failed, fragment will skip vector indexing fragmentId={}: {}",
                    fragment.getId(), e.getMessage());
            fragment.setL2Embedding(null);
        }
    }

    float[] resolveL2QueryEmbedding(String query, float[] l1QueryEmbedding) {
        if (l2EmbeddingService == null) {
            return l1QueryEmbedding;
        }
        try {
            return requireEmbedding(l2EmbeddingService, query, "L2 recall query");
        } catch (EmbeddingException e) {
            log.warn("L2 query embedding failed, falling back to L1 query embedding: {}", e.getMessage());
            return l1QueryEmbedding;
        }
    }

    float[] requireEmbedding(EmbeddingService embeddingService, String text, String context) {
        try {
            return embeddingService.embed(text);
        } catch (EmbeddingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EmbeddingException(context + " failed: " + e.getMessage(), e);
        }
    }

    // ---- Profile parsing ----

    Integer extractArmIndex(String profileName) {
        return parseProfileSuffix(profileName, "arm");
    }

    double extractSelectionProbability(String profileName) {
        Integer probabilityEncoded = parseProfileSuffix(profileName, "p");
        return probabilityEncoded == null ? 0.0 : probabilityEncoded / 10_000.0;
    }

    Integer parseProfileSuffix(String profileName, String marker) {
        if (profileName == null) {
            return null;
        }
        String token = "-" + marker;
        int start = profileName.indexOf(token);
        if (start < 0) {
            return null;
        }
        int valueStart = start + token.length();
        int valueEnd = valueStart;
        while (valueEnd < profileName.length() && Character.isDigit(profileName.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            return null;
        }
        return Integer.parseInt(profileName.substring(valueStart, valueEnd));
    }

    // ---- Validation ----

    private void validateL2DimensionCompatibility() {
        int configuredL2Dimension = l2.vectorDimension();
        if (configuredL2Dimension <= 0) {
            return;
        }

        int effectiveL2Dimension = (l2EmbeddingService != null)
                ? l2EmbeddingService.dimension()
                : l1EmbeddingService.dimension();

        if (configuredL2Dimension != effectiveL2Dimension) {
            String source = (l2EmbeddingService != null) ? "cloud embedding" : "local fallback embedding";
            throw new IllegalStateException(
                    "L2 vector dimension mismatch: store expects " + configuredL2Dimension
                            + " but " + source + " produces " + effectiveL2Dimension
                            + ". Align vortex.storage.l2.embedding-dim with the active embedding path.");
        }
    }
}
