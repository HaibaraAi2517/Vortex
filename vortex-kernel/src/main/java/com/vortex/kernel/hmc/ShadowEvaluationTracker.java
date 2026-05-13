package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ShadowEvaluationTracker {

    private static final int DEFAULT_MAX_WINDOW_SAMPLES = 2_048;

    private final double promotionThreshold;
    private final Duration promotionWindow;
    private final int minSamplesBeforePromotion;
    private final int maxWindowSamples;
    private final Path persistencePath;
    private final ObjectMapper objectMapper;
    private final Object persistenceLock = new Object();
    private final ConcurrentHashMap<String, ShadowProfileStats> statsByScenario = new ConcurrentHashMap<>();

    public ShadowEvaluationTracker(
            @Value("${vortex.kernel.learning.shadow-promotion-threshold:0.20}") double promotionThreshold,
            @Value("${vortex.kernel.learning.shadow-promotion-window-days:14}") long promotionWindowDays,
            @Value("${vortex.kernel.learning.min-samples-before-promotion:20}") int minSamplesBeforePromotion,
            @Value("${vortex.kernel.learning.shadow-persistence-path:${java.io.tmpdir}/vortex-hmc-shadow-eval.json}") String persistencePath) {
        this(
                promotionThreshold,
                promotionWindowDays,
                minSamplesBeforePromotion,
                DEFAULT_MAX_WINDOW_SAMPLES,
                Path.of(persistencePath),
                new ObjectMapper().findAndRegisterModules());
    }

    ShadowEvaluationTracker(
            double promotionThreshold,
            long promotionWindowDays) {
        this(
                promotionThreshold,
                promotionWindowDays,
                1,
                DEFAULT_MAX_WINDOW_SAMPLES,
                null,
                new ObjectMapper().findAndRegisterModules());
    }

    ShadowEvaluationTracker(
            double promotionThreshold,
            long promotionWindowDays,
            int minSamplesBeforePromotion,
            int maxWindowSamples,
            Path persistencePath,
            ObjectMapper objectMapper) {
        this.promotionThreshold = promotionThreshold;
        this.promotionWindow = Duration.ofDays(Math.max(0L, promotionWindowDays));
        this.minSamplesBeforePromotion = Math.max(1, minSamplesBeforePromotion);
        this.maxWindowSamples = Math.max(32, maxWindowSamples);
        this.persistencePath = persistencePath;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadPersistedState() {
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            ensurePersistenceFile();
            try {
                String content = Files.readString(persistencePath, StandardCharsets.UTF_8);
                if (content == null || content.isBlank()) {
                    return;
                }
                PersistedTrackerState persisted = objectMapper.readValue(content, PersistedTrackerState.class);
                if (persisted == null || persisted.scenarios == null) {
                    return;
                }
                statsByScenario.clear();
                for (Map.Entry<String, PersistedScenarioState> entry : persisted.scenarios.entrySet()) {
                    statsByScenario.put(entry.getKey(), ShadowProfileStats.fromPersisted(entry.getValue(), maxWindowSamples));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load shadow evaluation tracker state from " + persistencePath, e);
            }
        }
    }

    public void recordEvaluation(String scenarioKey, List<String> activeRanked, List<String> shadowRanked, Set<String> relevantIds) {
        recordEvaluation(scenarioKey, activeRanked, shadowRanked, activeRanked, List.of(), List.of(), List.of(), relevantIds);
    }

    public void recordEvaluation(
            String scenarioKey,
            List<String> activeRanked,
            List<String> shadowRanked,
            List<String> baselineRanked,
            Set<String> relevantIds) {
        recordEvaluation(scenarioKey, activeRanked, shadowRanked, baselineRanked, List.of(), List.of(), List.of(), relevantIds);
    }

    public void recordEvaluation(
            String scenarioKey,
            List<String> activeRecallRanked,
            List<String> shadowRecallRanked,
            List<String> baselineRecallRanked,
            List<String> activeEvictionRanked,
            List<String> shadowEvictionRanked,
            List<String> baselineEvictionRanked,
            Set<String> relevantIds) {
        ShadowProfileStats stats = statsByScenario.computeIfAbsent(scenarioKey, ignored -> new ShadowProfileStats(maxWindowSamples));
        synchronized (stats) {
            double activeRecallNdcg = ndcg(activeRecallRanked, relevantIds);
            double shadowRecallNdcg = ndcg(shadowRecallRanked, relevantIds);
            double baselineRecallNdcg = ndcg(baselineRecallRanked, relevantIds);
            double activeEvictionScore = evictionUtility(activeEvictionRanked, relevantIds);
            double shadowEvictionScore = evictionUtility(shadowEvictionRanked, relevantIds);
            double baselineEvictionScore = evictionUtility(baselineEvictionRanked, relevantIds);
            double activeComposite = compositeScore(activeRecallNdcg, activeEvictionScore);
            double shadowComposite = compositeScore(shadowRecallNdcg, shadowEvictionScore);
            double baselineComposite = compositeScore(baselineRecallNdcg, baselineEvictionScore);
            Instant now = Instant.now();

            stats.append(new EvaluationRecord(
                    now,
                    activeRecallNdcg,
                    shadowRecallNdcg,
                    baselineRecallNdcg,
                    activeEvictionScore,
                    shadowEvictionScore,
                    baselineEvictionScore,
                    activeComposite,
                    shadowComposite,
                    baselineComposite));

            if (shadowComposite >= activeComposite * (1.0 + promotionThreshold)) {
                stats.promotionWindowStart.compareAndSet(null, now);
            } else {
                stats.promotionWindowStart.set(null);
            }
        }
        persist();
    }

    public ShadowEvaluationSnapshot snapshot(String scenarioKey) {
        ShadowProfileStats stats = statsByScenario.get(scenarioKey);
        if (stats == null) {
            return ShadowEvaluationSnapshot.empty();
        }
        synchronized (stats) {
            return stats.snapshot(promotionThreshold, promotionWindow, minSamplesBeforePromotion);
        }
    }

    public void resetScenario(String scenarioKey) {
        statsByScenario.remove(scenarioKey);
        persist();
    }

    private void persist() {
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            ensurePersistenceFile();
            PersistedTrackerState persisted = new PersistedTrackerState();
            persisted.scenarios = new LinkedHashMap<>();
            for (Map.Entry<String, ShadowProfileStats> entry : statsByScenario.entrySet()) {
                synchronized (entry.getValue()) {
                    persisted.scenarios.put(entry.getKey(), entry.getValue().toPersisted());
                }
            }
            try {
                Files.writeString(
                        persistencePath,
                        objectMapper.writeValueAsString(persisted),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to persist shadow evaluation tracker state to " + persistencePath, e);
            }
        }
    }

    private void ensurePersistenceFile() {
        if (persistencePath == null) {
            return;
        }
        try {
            Path parent = persistencePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(persistencePath)) {
                Files.createFile(persistencePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize shadow evaluation tracker state file " + persistencePath, e);
        }
    }

    private double compositeScore(double recallNdcg, double evictionScore) {
        return (recallNdcg * 0.7) + (evictionScore * 0.3);
    }

    private double evictionUtility(List<String> evictionRankedIds, Set<String> relevantIds) {
        if (evictionRankedIds == null || evictionRankedIds.isEmpty()) {
            return 0.0;
        }
        int relevantInTop = 0;
        int inspected = 0;
        for (String fragmentId : evictionRankedIds) {
            inspected++;
            if (relevantIds != null && relevantIds.contains(fragmentId)) {
                relevantInTop++;
            }
        }
        return 1.0 - (relevantInTop / (double) inspected);
    }

    private double ndcg(List<String> rankedIds, Set<String> relevantIds) {
        if (rankedIds == null || rankedIds.isEmpty() || relevantIds == null || relevantIds.isEmpty()) {
            return 0.0;
        }
        double dcg = 0.0;
        for (int i = 0; i < rankedIds.size(); i++) {
            if (relevantIds.contains(rankedIds.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealHits = Math.min(rankedIds.size(), relevantIds.size());
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static final class ShadowProfileStats {
        private final int maxWindowSamples;
        private final Deque<EvaluationRecord> evaluations;
        private final AtomicReference<Instant> promotionWindowStart = new AtomicReference<>();
        private long totalSamples;

        private ShadowProfileStats(int maxWindowSamples) {
            this.maxWindowSamples = maxWindowSamples;
            this.evaluations = new ArrayDeque<>(maxWindowSamples);
        }

        private static ShadowProfileStats fromPersisted(PersistedScenarioState persisted, int maxWindowSamples) {
            ShadowProfileStats stats = new ShadowProfileStats(maxWindowSamples);
            stats.totalSamples = persisted.totalSamples;
            stats.promotionWindowStart.set(persisted.promotionWindowStart);
            if (persisted.recentEvaluations != null) {
                int start = Math.max(0, persisted.recentEvaluations.size() - maxWindowSamples);
                for (int i = start; i < persisted.recentEvaluations.size(); i++) {
                    stats.evaluations.addLast(persisted.recentEvaluations.get(i));
                }
            }
            return stats;
        }

        private void append(EvaluationRecord record) {
            if (evaluations.size() == maxWindowSamples) {
                evaluations.removeFirst();
            }
            evaluations.addLast(record);
            totalSamples++;
        }

        private ShadowEvaluationSnapshot snapshot(
                double promotionThreshold,
                Duration promotionWindow,
                int minSamplesBeforePromotion) {
            if (evaluations.isEmpty()) {
                return ShadowEvaluationSnapshot.empty();
            }
            double activeRecallAvg = evaluations.stream().mapToDouble(EvaluationRecord::activeRecallNdcg).average().orElse(0.0);
            double shadowRecallAvg = evaluations.stream().mapToDouble(EvaluationRecord::shadowRecallNdcg).average().orElse(0.0);
            double baselineRecallAvg = evaluations.stream().mapToDouble(EvaluationRecord::baselineRecallNdcg).average().orElse(0.0);
            double activeEvictionAvg = evaluations.stream().mapToDouble(EvaluationRecord::activeEvictionScore).average().orElse(0.0);
            double shadowEvictionAvg = evaluations.stream().mapToDouble(EvaluationRecord::shadowEvictionScore).average().orElse(0.0);
            double baselineEvictionAvg = evaluations.stream().mapToDouble(EvaluationRecord::baselineEvictionScore).average().orElse(0.0);
            double activeCompositeAvg = evaluations.stream().mapToDouble(EvaluationRecord::activeCompositeScore).average().orElse(0.0);
            double shadowCompositeAvg = evaluations.stream().mapToDouble(EvaluationRecord::shadowCompositeScore).average().orElse(0.0);
            double baselineCompositeAvg = evaluations.stream().mapToDouble(EvaluationRecord::baselineCompositeScore).average().orElse(0.0);
            double relativeLift = activeCompositeAvg == 0.0 ? 0.0 : (shadowCompositeAvg - activeCompositeAvg) / activeCompositeAvg;
            double baselineRelativeLift = baselineCompositeAvg == 0.0 ? 0.0 : (activeCompositeAvg - baselineCompositeAvg) / baselineCompositeAvg;
            double shadowWinRatio = evaluations.stream()
                    .filter(record -> record.shadowCompositeScore() >= record.activeCompositeScore() * (1.0 + promotionThreshold))
                    .count() / (double) evaluations.size();
            double baselineWinRatio = evaluations.stream()
                    .filter(record -> record.activeCompositeScore() >= record.baselineCompositeScore() * (1.0 + promotionThreshold))
                    .count() / (double) evaluations.size();
            Instant promotionStart = promotionWindowStart.get();
            boolean eligibleForPromotion = promotionStart != null
                    && totalSamples >= minSamplesBeforePromotion
                    && shadowWinRatio >= 0.90
                    && Duration.between(promotionStart, Instant.now()).compareTo(promotionWindow) >= 0
                    && relativeLift >= promotionThreshold;
            return new ShadowEvaluationSnapshot(
                    activeRecallAvg,
                    shadowRecallAvg,
                    baselineRecallAvg,
                    activeEvictionAvg,
                    shadowEvictionAvg,
                    baselineEvictionAvg,
                    activeCompositeAvg,
                    shadowCompositeAvg,
                    baselineCompositeAvg,
                    relativeLift,
                    baselineRelativeLift,
                    shadowWinRatio,
                    baselineWinRatio,
                    totalSamples,
                    eligibleForPromotion,
                    promotionStart);
        }

        private PersistedScenarioState toPersisted() {
            PersistedScenarioState persisted = new PersistedScenarioState();
            persisted.totalSamples = totalSamples;
            persisted.promotionWindowStart = promotionWindowStart.get();
            persisted.recentEvaluations = List.copyOf(evaluations);
            return persisted;
        }
    }

    private record EvaluationRecord(
            Instant evaluatedAt,
            double activeRecallNdcg,
            double shadowRecallNdcg,
            double baselineRecallNdcg,
            double activeEvictionScore,
            double shadowEvictionScore,
            double baselineEvictionScore,
            double activeCompositeScore,
            double shadowCompositeScore,
            double baselineCompositeScore) {
    }

    @lombok.Data
    private static final class PersistedTrackerState {
        private Map<String, PersistedScenarioState> scenarios;
    }

    @lombok.Data
    private static final class PersistedScenarioState {
        private long totalSamples;
        private Instant promotionWindowStart;
        private List<EvaluationRecord> recentEvaluations;
    }

    public record ShadowEvaluationSnapshot(
            double activeAverageNdcg,
            double shadowAverageNdcg,
            double baselineAverageNdcg,
            double activeEvictionUtility,
            double shadowEvictionUtility,
            double baselineEvictionUtility,
            double activeCompositeScore,
            double shadowCompositeScore,
            double baselineCompositeScore,
            double relativeLift,
            double baselineRelativeLift,
            double shadowWinRate,
            double baselineWinRate,
            long sampleCount,
            boolean eligibleForPromotion,
            Instant promotionWindowStart) {

        private static ShadowEvaluationSnapshot empty() {
            return new ShadowEvaluationSnapshot(
                    0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0,
                    0.0, 0.0,
                    0.0, 0.0,
                    0L,
                    false,
                    null);
        }
    }
}
