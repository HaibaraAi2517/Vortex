package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.serialization.JsonMapperFactory;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.hmc.AdaptiveWeightLearner;
import com.vortex.kernel.hmc.AdaptiveWeightProfile;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.hmc.ShadowEvaluationTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningMemoryEvalRunnerTest {

    private final HierarchicalMemoryController hmc = mock(HierarchicalMemoryController.class);
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
    private final ObjectMapper objectMapper = JsonMapperFactory.create();
    private final LearningMemoryEvalProperties properties = new LearningMemoryEvalProperties();
    private final LearningMemoryEvalGateEvaluator gateEvaluator = new LearningMemoryEvalGateEvaluator(properties);
    private final TokenCounter tokenCounter = text -> text == null || text.isBlank()
            ? 0
            : text.trim().split("\\s+").length;
    private final Map<String, List<MemoryFragment>> fragmentsByNamespace = new ConcurrentHashMap<>();
    private final List<String> events = new ArrayList<>();
    private final AtomicInteger recallSequence = new AtomicInteger();
    private final AtomicLong learningSamples = new AtomicLong();
    private final AtomicLong activeUpdates = new AtomicLong();

    private LearningMemoryEvalRunner runner;

    @BeforeEach
    void setUp() {
        properties.setMinScenarioCount(1);
        properties.setMinFeedbackSampleCount(2);
        properties.setMinProbeAllRelevantHitRate(1.0d);
        fragmentsByNamespace.clear();
        events.clear();
        recallSequence.set(0);
        learningSamples.set(0);
        activeUpdates.set(0);
        runner = new LearningMemoryEvalRunner(
                hmc,
                resourceLoader,
                objectMapper,
                properties,
                gateEvaluator,
                tokenCounter);

        doAnswer(invocation -> {
            MemoryFragment fragment = invocation.getArgument(0);
            fragmentsByNamespace
                    .computeIfAbsent(fragment.getNamespace(), ignored -> new ArrayList<>())
                    .add(fragment);
            return null;
        }).when(hmc).storeFragment(any(MemoryFragment.class));

        when(hmc.recall(any(RecallQuery.class))).thenAnswer(invocation -> {
            RecallQuery query = invocation.getArgument(0);
            events.add("recall:" + query.getNamespace());
            List<MemoryFragment> fragments = fragmentsByNamespace.getOrDefault(query.getNamespace(), List.of());
            List<RecallResult.ScoredFragment> scoredFragments = fragments.stream()
                    .limit(query.getTopK())
                    .map(fragment -> RecallResult.ScoredFragment.builder()
                            .fragment(fragment)
                            .score(fragment.getImportance())
                            .tier("L1")
                            .build())
                    .toList();
            return RecallResult.builder()
                    .fragments(scoredFragments)
                    .totalTokens(scoredFragments.stream()
                            .map(RecallResult.ScoredFragment::getFragment)
                            .mapToInt(MemoryFragment::getTokenCount)
                            .sum())
                    .sourceTrace(scoredFragments.stream().map(RecallResult.ScoredFragment::getTier).toList())
                    .recallSessionId("learning-session-" + recallSequence.incrementAndGet())
                    .activeProfileName("chat-active-arm0-p10000")
                    .shadowProfileName("chat-shadow-arm1-p10000")
                    .build();
        });

        doAnswer(invocation -> {
            events.add("feedback");
            learningSamples.incrementAndGet();
            activeUpdates.incrementAndGet();
            return null;
        }).when(hmc).recordFeedback(any(MemoryFeedbackRequest.class));

        when(hmc.learningSnapshot(any())).thenAnswer(invocation -> learningSnapshot(
                learningSamples.get(),
                activeUpdates.get()));
    }

    @Test
    void loadDefaultCaseSetShouldParseLearningDataset() {
        List<LearningMemoryEvalCase> cases = runner.loadDefaultCaseSet();

        assertThat(cases).hasSize(5);
        assertThat(cases)
                .allSatisfy(evalCase -> {
                    assertThat(evalCase.getCalibrationQueries()).hasSize(6);
                    assertThat(evalCase.getProbeQueries()).hasSize(2);
                    assertThat(evalCase.getFragments()).hasSize(6);
                });
    }

    @Test
    void runShouldStoreRecallFeedbackAndEvaluateGate() {
        LearningMemoryEvalCase evalCase = LearningMemoryEvalCase.builder()
                .scenarioId("learning-test-001")
                .namespace("learning-test")
                .memoryScenario(MemoryScenario.CHAT)
                .topK(2)
                .tokenBudget(256)
                .fragments(List.of(
                        LearningMemoryEvalCase.LearningMemoryFragment.builder()
                                .fragmentId("current-policy")
                                .content("Current policy uses the read-only console.")
                                .relevant(true)
                                .importance(0.99d)
                                .build(),
                        LearningMemoryEvalCase.LearningMemoryFragment.builder()
                                .fragmentId("old-policy")
                                .content("Old sandbox policy used a write console.")
                                .relevant(false)
                                .importance(0.10d)
                                .build()))
                .calibrationQueries(List.of("Which policy is current?"))
                .probeQueries(List.of("Which console should be used now?"))
                .feedback(LearningMemoryEvalCase.FeedbackSpec.builder()
                        .usedFragmentIds(List.of("current-policy"))
                        .answerAccepted(true)
                        .build())
                .build();

        LearningMemoryEvalReport report = runner.run(List.of(evalCase));

        assertThat(report.isGatePassed()).isTrue();
        assertThat(report.getScenarioCount()).isEqualTo(1);
        assertThat(report.getTotalRecallCount()).isEqualTo(2);
        assertThat(report.getFeedbackSubmitted()).isEqualTo(2);
        assertThat(report.getAggregate().getFeedbackSampleCount()).isEqualTo(2);
        assertThat(report.getAggregate().getPendingRecallSessions()).isZero();
        assertThat(report.getAggregate().getActiveUpdateCountAfter())
                .isGreaterThan(report.getAggregate().getActiveUpdateCountBefore());
        assertThat(report.getAggregate().getProbeAllRelevantHitRate()).isEqualTo(1.0d);
        assertThat(report.getScenarios()).singleElement()
                .satisfies(scenario -> assertThat(scenario.getObservations())
                        .extracting(LearningMemoryEvalReport.RecallObservation::getReturnedFragmentIds)
                        .allSatisfy(ids -> assertThat(ids).contains("current-policy")));
        verify(hmc, times(2)).recordFeedback(any(MemoryFeedbackRequest.class));
    }

    @Test
    void runShouldCaptureAllInitialRecallsBeforeSubmittingLearningFeedback() {
        LearningMemoryEvalCase first = learningCase("learning-test-a", "current-a", "old-a");
        LearningMemoryEvalCase second = learningCase("learning-test-b", "current-b", "old-b");

        runner.run(List.of(first, second));

        assertThat(events).hasSize(8);
        assertThat(events.subList(0, 2)).allMatch(event -> event.startsWith("recall:"));
        assertThat(events.subList(2, 4)).containsOnly("feedback");
    }

    private AdaptiveWeightLearner.LearningSnapshot learningSnapshot(long samples, long updates) {
        return new AdaptiveWeightLearner.LearningSnapshot(
                AdaptiveWeightProfile.builder()
                        .profileName("chat-active-arm0-p10000")
                        .alpha(0.3d)
                        .beta(0.5d)
                        .gamma(0.2d)
                        .updateCount(updates)
                        .updatedAt(Instant.now())
                        .build(),
                AdaptiveWeightProfile.builder()
                        .profileName("chat-shadow-arm1-p10000")
                        .alpha(0.2d)
                        .beta(0.6d)
                        .gamma(0.2d)
                        .updateCount(0L)
                        .updatedAt(Instant.now())
                        .build(),
                new ShadowEvaluationTracker.ShadowEvaluationSnapshot(
                        samples == 0L ? 0.0d : 1.0d,
                        samples == 0L ? 0.0d : 1.0d,
                        samples == 0L ? 0.0d : 1.0d,
                        samples == 0L ? 0.0d : 1.0d,
                        samples == 0L ? 0.0d : 1.0d,
                        samples == 0L ? 0.0d : 1.0d,
                        samples == 0L ? 0.0d : 1.0d,
                        samples == 0L ? 0.0d : 1.0d,
                        samples == 0L ? 0.0d : 1.0d,
                        0.0d,
                        0.0d,
                        0.0d,
                        0.0d,
                        samples,
                        false,
                        null),
                0,
                null);
    }

    private LearningMemoryEvalCase learningCase(String scenarioId, String relevantId, String distractorId) {
        return LearningMemoryEvalCase.builder()
                .scenarioId(scenarioId)
                .namespace("learning-test")
                .memoryScenario(MemoryScenario.CHAT)
                .topK(1)
                .tokenBudget(256)
                .fragments(List.of(
                        LearningMemoryEvalCase.LearningMemoryFragment.builder()
                                .fragmentId(relevantId)
                                .content("Current retained policy.")
                                .relevant(true)
                                .importance(0.99d)
                                .build(),
                        LearningMemoryEvalCase.LearningMemoryFragment.builder()
                                .fragmentId(distractorId)
                                .content("Old policy.")
                                .relevant(false)
                                .importance(0.10d)
                                .build()))
                .calibrationQueries(List.of("initial calibration"))
                .probeQueries(List.of("probe"))
                .feedback(LearningMemoryEvalCase.FeedbackSpec.builder()
                        .usedFragmentIds(List.of(relevantId))
                        .answerAccepted(true)
                        .build())
                .build();
    }
}
