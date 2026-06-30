package com.vortex.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.GenerationLatencyBreakdown;
import com.vortex.common.dto.GenerationRequest;
import com.vortex.common.dto.GenerationResult;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.RecallDiagnostics;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.dto.RetrievalMode;
import com.vortex.common.exception.GenerationException;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.serialization.JsonMapperFactory;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.generation.GenerationService;
import com.vortex.kernel.hmc.AdaptiveWeightLearner;
import com.vortex.kernel.hmc.AdaptiveWeightProfile;
import com.vortex.kernel.hmc.ShadowEvaluationTracker;
import com.vortex.kernel.generation.PromptAssembler;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmMemoryEvalRunnerTest {

    private final HierarchicalMemoryController hmc = mock(HierarchicalMemoryController.class);
    private final ObjectProvider<GenerationService> generationServiceProvider = mock(ObjectProvider.class);
    private final L2WarmStore l2WarmStore = mock(L2WarmStore.class);
    private final TokenCounter tokenCounter = text -> {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    };
    private final PromptAssembler promptAssembler = new PromptAssembler(tokenCounter, 256);
    private final RuleBasedAnswerJudge answerJudge = new RuleBasedAnswerJudge();
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
    private final ObjectMapper objectMapper = JsonMapperFactory.create();
    private final LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
    private final Map<String, List<MemoryFragment>> fragmentsByNamespace = new ConcurrentHashMap<>();
    private final Map<String, MemoryFragment> l2FragmentsById = new ConcurrentHashMap<>();
    private final List<MemoryFragment> storedFragments = new ArrayList<>();
    private final TestL1HotStore l1HotStore = new TestL1HotStore(96);
    private Map<String, String> expectedAnswers;
    private final AtomicLong learningSampleCount = new AtomicLong();
    private final AtomicLong learningUpdateCount = new AtomicLong();
    private final AtomicBoolean evictRecoveredTargetsOnFiller = new AtomicBoolean(true);
    private final AtomicBoolean assertDelayedL2VisibleOnRecall = new AtomicBoolean();
    private final AtomicInteger requiredFillerInsertionsBeforeEviction = new AtomicInteger(1);
    private final AtomicInteger minimumFillerTokensBeforeEviction = new AtomicInteger();
    private final AtomicInteger observedRecoveryFillerInsertions = new AtomicInteger();
    private final Map<String, AtomicInteger> l2VisibilityDelaysByIdPart = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> l2WrongNamespaceReadsByIdPart = new ConcurrentHashMap<>();

    private LlmMemoryEvalRunner runner;

    @BeforeEach
    void setUp() {
        fragmentsByNamespace.clear();
        l2FragmentsById.clear();
        storedFragments.clear();
        l1HotStore.clearAll();
        assertDelayedL2VisibleOnRecall.set(false);
        l2VisibilityDelaysByIdPart.clear();
        l2WrongNamespaceReadsByIdPart.clear();
        requiredFillerInsertionsBeforeEviction.set(1);
        minimumFillerTokensBeforeEviction.set(0);
        observedRecoveryFillerInsertions.set(0);
        runner = new LlmMemoryEvalRunner(
                hmc,
                promptAssembler,
                answerJudge,
                generationServiceProvider,
                l2WarmStore,
                resourceLoader,
                objectMapper,
                properties,
                tokenCounter);
        expectedAnswers = loadExpectedAnswers();
        GenerationService generationService = request -> {
            String caseId = request.getMetadata().get("caseId");
            String expectedAnswer = expectedAnswers.get(caseId);
            String answer = request.getUserPrompt().contains(expectedAnswer)
                    ? expectedAnswer
                    : "memory insufficient";
            return GenerationResult.builder()
                    .content(answer)
                    .model("provider-gpt-5.4")
                    .latencyMs(31L)
                    .latencyBreakdown(GenerationLatencyBreakdown.builder()
                            .requestBuildLatencyMs(3L)
                            .httpRoundTripLatencyMs(23L)
                            .responseParseLatencyMs(5L)
                            .retryBackoffLatencyMs(0L)
                            .totalLatencyMs(31L)
                            .requestBuildLatencyNanos(3_400_000L)
                            .requestSerializationLatencyNanos(1_200_000L)
                            .httpRequestBuildLatencyNanos(2_200_000L)
                            .httpRoundTripLatencyNanos(23_700_000L)
                            .responseParseLatencyNanos(5_600_000L)
                            .responseDecodeLatencyNanos(1_300_000L)
                            .responseJsonParseLatencyNanos(4_300_000L)
                            .retryBackoffLatencyNanos(0L)
                            .totalLatencyNanos(31_000_000L)
                            .attemptCount(1)
                            .httpStatusCode(200)
                            .requestBytes(321)
                            .responseBytes(654)
                            .build())
                    .promptTokens(tokenCounter.countTokens(
                            request.getSystemPrompt() + " " + request.getUserPrompt()))
                    .completionTokens(tokenCounter.countTokens(answer))
                    .build();
        };
        when(generationServiceProvider.getIfAvailable()).thenReturn(generationService);
        when(hmc.getL1()).thenReturn(l1HotStore);
        when(l2WarmStore.get(any(String.class))).thenAnswer(invocation -> {
            String fragmentId = invocation.getArgument(0);
            for (Map.Entry<String, AtomicInteger> entry : l2WrongNamespaceReadsByIdPart.entrySet()) {
                if (fragmentId.contains(entry.getKey()) && entry.getValue().get() > 0) {
                    entry.getValue().decrementAndGet();
                    MemoryFragment fragment = l2FragmentsById.get(fragmentId);
                    if (fragment != null) {
                        return Optional.of(MemoryFragment.builder()
                                .id(fragment.getId())
                                .namespace("stale-mode-namespace")
                                .content(fragment.getContent())
                                .embedding(fragment.getEmbedding())
                                .l2Embedding(fragment.getL2Embedding())
                                .tokenCount(fragment.getTokenCount())
                                .importance(fragment.getImportance())
                                .lastAccessTime(fragment.getLastAccessTime())
                                .createdAt(fragment.getCreatedAt())
                                .tags(fragment.getTags())
                                .reasoningChainId(fragment.getReasoningChainId())
                                .pinnedUntil(fragment.getPinnedUntil())
                                .build());
                    }
                }
            }
            for (Map.Entry<String, AtomicInteger> entry : l2VisibilityDelaysByIdPart.entrySet()) {
                if (fragmentId.contains(entry.getKey()) && entry.getValue().get() > 0) {
                    entry.getValue().decrementAndGet();
                    return Optional.empty();
                }
            }
            return Optional.ofNullable(l2FragmentsById.get(fragmentId));
        });
        when(hmc.learningSnapshot(any())).thenAnswer(invocation ->
                learningSnapshot(learningSampleCount.get(), learningUpdateCount.get()));

        doAnswer(invocation -> {
            MemoryFragment fragment = invocation.getArgument(0);
            storedFragments.add(fragment);
            fragmentsByNamespace
                    .computeIfAbsent(fragment.getNamespace(), ignored -> new ArrayList<>())
                    .add(fragment);
            l1HotStore.put(fragment);
            l2FragmentsById.put(fragment.getId(), fragment);
            if (fragment.getId().contains("eviction-filler-") && evictRecoveredTargetsOnFiller.get()) {
                int fillerInsertions = observedRecoveryFillerInsertions.incrementAndGet();
                if (fillerInsertions >= requiredFillerInsertionsBeforeEviction.get()
                        && fragment.getTokenCount() >= minimumFillerTokensBeforeEviction.get()) {
                    l1HotStore.evictRecoveredTargets();
                }
            }
            return null;
        }).when(hmc).storeFragment(any(MemoryFragment.class));

        doAnswer(invocation -> {
            String fragmentId = invocation.getArgument(0);
            fragmentsByNamespace.values().forEach(fragments ->
                    fragments.removeIf(fragment -> fragmentId.equals(fragment.getId())));
            l1HotStore.remove(fragmentId);
            l2FragmentsById.remove(fragmentId);
            return true;
        }).when(hmc).deleteFragment(any(String.class));

        doAnswer(invocation -> {
            learningSampleCount.incrementAndGet();
            learningUpdateCount.incrementAndGet();
            return null;
        }).when(hmc).recordFeedback(any(MemoryFeedbackRequest.class));

        when(hmc.recall(any(RecallQuery.class))).thenAnswer(invocation -> {
            if (assertDelayedL2VisibleOnRecall.get()) {
                assertThat(l2VisibilityDelaysByIdPart.values())
                        .allSatisfy(remaining -> assertThat(remaining.get()).isZero());
            }
            RecallQuery query = invocation.getArgument(0);
            List<MemoryFragment> fragments = fragmentsByNamespace.getOrDefault(query.getNamespace(), List.of());
            List<String> requiredTags = query.getTags() == null ? List.of() : query.getTags();
            List<RecallResult.ScoredFragment> scoredFragments = fragments.stream()
                    .filter(fragment -> fragmentMatchesTags(fragment, requiredTags))
                    .filter(fragment -> query.getRetrievalMode() != RetrievalMode.VECTOR_ONLY
                            || !fragment.getId().contains("keyword-only"))
                    .limit(query.getTopK())
                    .map(fragment -> RecallResult.ScoredFragment.builder()
                            .fragment(fragment)
                            .score(0.95d)
                            .tier(l1HotStore.peek(fragment.getId()).isPresent() ? "L1" : "L2")
                            .build())
                    .toList();
            return RecallResult.builder()
                    .fragments(scoredFragments)
                    .totalTokens(scoredFragments.stream()
                            .map(RecallResult.ScoredFragment::getFragment)
                            .mapToInt(MemoryFragment::getTokenCount)
                            .sum())
                    .recallSessionId("recall-" + query.getNamespace())
                    .sourceTrace(scoredFragments.stream().map(RecallResult.ScoredFragment::getTier).toList())
                    .diagnostics(RecallDiagnostics.builder()
                            .requiredTags(requiredTags)
                            .retrievalMode(query.getRetrievalMode() == null ? null : query.getRetrievalMode().name())
                            .l1CandidateCount(fragments.size())
                            .l1TagMatchedCount(scoredFragments.size())
                            .l1SelectedCount((int) scoredFragments.stream()
                                    .filter(fragment -> "L1".equals(fragment.getTier()))
                                    .count())
                            .keywordCandidateCount(query.getRetrievalMode() == RetrievalMode.VECTOR_ONLY ? 0 : scoredFragments.size())
                            .vectorCandidateCount(scoredFragments.size())
                            .rerankCandidateCount(scoredFragments.size())
                            .l2SearchCandidateCount(fragments.size())
                            .l2SearchAcceptedCount((int) scoredFragments.stream()
                                    .filter(fragment -> "L2".equals(fragment.getTier()))
                                    .count())
                            .finalReturnedCount(scoredFragments.size())
                            .build())
                    .build();
        });
    }

    @Test
    void runDefaultBaselinesShouldShowAccuracyLiftFromMemory() {
        LlmMemoryEvalReport report = runner.runDefaultBaselines();

        assertThat(report.getTotalCases()).isEqualTo(20);
        assertThat(report.getTotalRuns()).isEqualTo(40);
        assertThat(report.getResults()).hasSize(40);
        assertThat(report.getModeSummaries().get("Baseline-NoMemory").getAccuracy()).isEqualTo(0.0d);
        assertThat(report.getModeSummaries().get("Vortex-Memory").getAccuracy()).isEqualTo(1.0d);
        assertThat(report.getModeSummaries().get("Vortex-Memory").getRecallHitRate()).isEqualTo(1.0d);
        assertThat(report.getResults())
                .extracting(LlmMemoryEvalResult::getActualGenerationModel)
                .containsOnly("provider-gpt-5.4");
        assertThat(report.getRuntimeTelemetry()).satisfies(telemetry -> {
            assertThat(telemetry.getConfiguredParallelism()).isEqualTo(1);
            assertThat(telemetry.getActualWorkerCount()).isEqualTo(1);
            assertThat(telemetry.isModePhasedParallel()).isFalse();
            assertThat(telemetry.getTotalElapsedMs()).isGreaterThanOrEqualTo(0L);
            assertThat(telemetry.getModePhaseTimings()).isEmpty();
        });
        assertThat(report.getResults().stream()
                .filter(result -> "Vortex-Memory".equals(result.getMode()))
                .allMatch(LlmMemoryEvalResult::isCorrect))
                .isTrue();
    }

    @Test
    void runShouldReportHybridRecallLiftAgainstVectorOnly() {
        LlmMemoryEvalCase evalCase = LlmMemoryEvalCase.builder()
                .caseId("keyword-lift-001")
                .namespace("llm-eval-keyword-lift")
                .memoryFragments(List.of(LlmMemoryEvalCase.EvalMemoryFragment.builder()
                        .fragmentId("keyword-only-owner")
                        .content("Pegasus owner is avery-deploy@example.com")
                        .tags(List.of("keyword-lift"))
                        .build()))
                .question("Which Pegasus owner email should be used?")
                .expectedAnswer("avery-deploy@example.com")
                .mustContain(List.of("avery-deploy@example.com"))
                .expectedFragments(List.of("keyword-only-owner"))
                .tags(List.of("keyword-lift"))
                .build();
        expectedAnswers.put("keyword-lift-001", "avery-deploy@example.com");

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(
                LlmMemoryEvalMode.VORTEX_VECTOR_ONLY,
                LlmMemoryEvalMode.VORTEX_MEMORY));

        LlmMemoryEvalReport.ModeSummary vectorOnly = report.getModeSummaries().get("Vortex-VectorOnly");
        LlmMemoryEvalReport.ModeSummary hybrid = report.getModeSummaries().get("Vortex-Memory");
        assertThat(vectorOnly.getRecallHitRate()).isEqualTo(0.0d);
        assertThat(hybrid.getRecallHitRate()).isEqualTo(1.0d);
        assertThat(hybrid.getRecallHitRateLiftVsVectorOnly()).isEqualTo(1.0d);
    }
    @Test
    void loadDefaultCaseSetShouldReadRuntimeClasspathDataset() {
        List<LlmMemoryEvalCase> cases = runner.loadDefaultCaseSet();

        assertThat(cases).hasSize(20);
        assertThat(cases.getFirst().getCaseId()).isEqualTo("profile-001");
    }

    @Test
    void loadV2CaseSetShouldExposeCompositeAndDistractorCases() {
        List<LlmMemoryEvalCase> cases = runner.loadCaseSet("classpath:llm-memory-eval-set-v2.json");

        assertThat(cases).hasSize(15);
        assertThat(cases)
                .allSatisfy(evalCase -> assertThat(evalCase.getMemoryFragments()).isNotEmpty())
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v2-001");
                    assertThat(evalCase.getExpectedFragments()).hasSize(2);
                    assertThat(evalCase.getMemoryFragments()).hasSizeGreaterThan(evalCase.getExpectedFragments().size());
                });
        assertThat(cases.stream().filter(evalCase -> evalCase.getExpectedFragments().size() > 1)).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void loadV21CaseSetShouldMakeV2009SameWeekdayContractExplicit() {
        List<LlmMemoryEvalCase> cases = runner.loadCaseSet("classpath:llm-memory-eval-set-v2-1.json");

        assertThat(cases).hasSize(15);
        LlmMemoryEvalCase v2009 = cases.stream()
                .filter(evalCase -> "v2-009".equals(evalCase.getCaseId()))
                .findFirst()
                .orElseThrow();

        assertThat(v2009.getExpectedAnswer()).isEqualTo("Thursday");
        assertThat(v2009.getMemoryFragments())
                .anySatisfy(fragment -> {
                    assertThat(fragment.getFragmentId()).isEqualTo("mobile-cutoff");
                    assertThat(fragment.getContent()).contains("starts, on the same weekday");
                });
    }

    @Test
    void loadV21ExtendedCaseSetShouldBroadenMemoryCoverageWithoutReplacingOfficialV21() {
        List<LlmMemoryEvalCase> cases = runner.loadCaseSet("classpath:llm-memory-eval-set-v2-1-extended.json");

        assertThat(cases).hasSize(30);
        assertThat(cases.subList(0, 15))
                .extracting(LlmMemoryEvalCase::getCaseId)
                .containsExactly("v2-001", "v2-002", "v2-003", "v2-004", "v2-005",
                        "v2-006", "v2-007", "v2-008", "v2-009", "v2-010",
                        "v2-011", "v2-012", "v2-013", "v2-014", "v2-015");
        assertThat(cases)
                .extracting(LlmMemoryEvalCase::getCaseId)
                .contains("v2-016", "v2-018", "v2-023", "v2-025", "v2-030");
        assertThat(cases)
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v2-016");
                    assertThat(evalCase.getQuestion()).contains("Avery");
                    assertThat(evalCase.getExpectedAnswer()).isEqualTo("avery-deploy@example.com");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v2-025");
                    assertThat(evalCase.getExpectedFragments())
                            .containsExactly("migration-rhea-status", "billing-schema-review-status");
                    assertThat(evalCase.getExpectedAnswer()).isEqualTo("security approval");
                });
    }

    @Test
    void loadV3RealAgentWorkloadShouldFocusOnLongTaskAgentMemoryScenarios() {
        List<LlmMemoryEvalCase> cases =
                runner.loadCaseSet("classpath:llm-memory-eval-set-v3-real-agent-workload.json");

        assertThat(cases).hasSize(12);
        assertThat(cases)
                .extracting(LlmMemoryEvalCase::getCaseId)
                .containsExactly("v3-agent-001", "v3-agent-002", "v3-agent-003", "v3-agent-004",
                        "v3-agent-005", "v3-agent-006", "v3-agent-007", "v3-agent-008",
                        "v3-agent-009", "v3-agent-010", "v3-agent-011", "v3-agent-012");
        assertThat(cases)
                .allSatisfy(evalCase -> {
                    assertThat(evalCase.getTags()).contains("agent-workload");
                    assertThat(evalCase.getMemoryFragments()).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(evalCase.getExpectedFragments()).isNotEmpty();
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3-agent-001");
                    assertThat(evalCase.getMustNotContain()).containsExactly("Dana is the current incident commander");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3-agent-002");
                    assertThat(evalCase.getMustNotContain()).containsExactly("design signoff is the current blocker");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3-agent-003");
                    assertThat(evalCase.getMustNotContain()).containsExactly("use long narrative deployment summaries");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3-agent-004");
                    assertThat(evalCase.getFailureCategories()).contains("checkpoint_continuation");
                    assertThat(evalCase.getExpectedAnswer()).isEqualTo("rollback threshold");
                    assertThat(evalCase.getMustContain()).containsExactly("compare", "latency evidence", "rollback threshold");
                    assertThat(evalCase.getMustNotContain()).contains("ask SRE for traces");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3-agent-005");
                    assertThat(evalCase.getFailureCategories()).contains("branch_isolation");
                    assertThat(evalCase.getExpectedAnswer()).isEqualTo("Kafka compacted topics");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3-agent-010");
                    assertThat(evalCase.getFailureCategories()).contains("tool_policy", "safety");
                    assertThat(evalCase.getExpectedAnswer()).isEqualTo("read-only query tool");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3-agent-012");
                    assertThat(evalCase.getExpectedAnswer()).isEqualTo("memory recovery benchmark");
                    assertThat(evalCase.getMustContain()).containsExactly("prioritize", "memory recovery benchmark");
                });
    }

    @Test
    void runV3RealAgentWorkloadShouldMatchStrictBaselineWithoutCallingRealModel() {
        List<LlmMemoryEvalCase> cases =
                runner.loadCaseSet("classpath:llm-memory-eval-set-v3-real-agent-workload.json");
        assertV3StyleWorkloadMatchesStrictShapeWithoutCallingRealModel(cases, 12);
    }

    @Test
    void loadV31RealAgentWorkloadShouldBroadenHardAgentMemoryCoverageAsOfficialWorkload() {
        List<LlmMemoryEvalCase> cases =
                runner.loadCaseSet("classpath:llm-memory-eval-set-v3-1-real-agent-workload.json");

        assertThat(cases).hasSize(20);
        assertThat(cases)
                .extracting(LlmMemoryEvalCase::getCaseId)
                .containsExactly("v3.1-agent-001", "v3.1-agent-002", "v3.1-agent-003", "v3.1-agent-004",
                        "v3.1-agent-005", "v3.1-agent-006", "v3.1-agent-007", "v3.1-agent-008",
                        "v3.1-agent-009", "v3.1-agent-010", "v3.1-agent-011", "v3.1-agent-012",
                        "v3.1-agent-013", "v3.1-agent-014", "v3.1-agent-015", "v3.1-agent-016",
                        "v3.1-agent-017", "v3.1-agent-018", "v3.1-agent-019", "v3.1-agent-020");
        assertThat(cases)
                .allSatisfy(evalCase -> {
                    assertThat(evalCase.getTags()).contains("agent-workload");
                    assertThat(evalCase.getMemoryFragments()).hasSizeGreaterThanOrEqualTo(4);
                    assertThat(evalCase.getExpectedFragments()).isNotEmpty();
                    assertThat(evalCase.getFailureCategories()).isNotEmpty();
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3.1-agent-002");
                    assertThat(evalCase.getFailureCategories()).contains("namespace_collision");
                    assertThat(evalCase.getMustNotContain()).contains("Pavel");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3.1-agent-008");
                    assertThat(evalCase.getExpectedFragments()).hasSize(3);
                    assertThat(evalCase.getFailureCategories()).contains("multi_fragment_synthesis");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3.1-agent-018");
                    assertThat(evalCase.getFailureCategories()).contains("avoid_repeating_done_work");
                    assertThat(evalCase.getExpectedAnswer()).isEqualTo("compare");
                    assertThat(evalCase.getMustContain()).containsExactly("CDN logs", "origin latency samples");
                    assertThat(evalCase.getMustNotContain()).contains("gather CDN logs");
                })
                .anySatisfy(evalCase -> {
                    assertThat(evalCase.getCaseId()).isEqualTo("v3.1-agent-019");
                    assertThat(evalCase.getExpectedAnswer()).isEqualTo("APAC");
                    assertThat(evalCase.getMustContain()).containsExactly("APAC", "only");
                });
        assertThat(cases.stream().filter(evalCase -> evalCase.getExpectedFragments().size() >= 2))
                .hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    void runV31RealAgentWorkloadShouldPassDeterministicOfficialShapeWithoutCallingRealModel() {
        List<LlmMemoryEvalCase> cases =
                runner.loadCaseSet("classpath:llm-memory-eval-set-v3-1-real-agent-workload.json");
        assertV3StyleWorkloadMatchesStrictShapeWithoutCallingRealModel(cases, 20);
    }

    private void assertV3StyleWorkloadMatchesStrictShapeWithoutCallingRealModel(
            List<LlmMemoryEvalCase> cases,
            int expectedTotalCases) {
        Map<String, LlmMemoryEvalCase> casesById = cases.stream()
                .collect(java.util.stream.Collectors.toMap(
                        LlmMemoryEvalCase::getCaseId,
                        evalCase -> evalCase));
        when(generationServiceProvider.getIfAvailable()).thenReturn(request -> {
            String mode = request.getMetadata().get("mode");
            String caseId = request.getMetadata().get("caseId");
            LlmMemoryEvalCase evalCase = casesById.get(caseId);
            boolean hasExpectedMemory = evalCase != null && evalCase.getMemoryFragments().stream()
                    .filter(fragment -> evalCase.getExpectedFragments().contains(fragment.getFragmentId()))
                    .allMatch(fragment -> request.getUserPrompt().contains(fragment.getContent()));
            String answer = !"Baseline-NoMemory".equals(mode) && hasExpectedMemory
                    ? deterministicPassingAnswer(evalCase)
                    : "memory insufficient";
            return GenerationResult.builder()
                    .content(answer)
                    .latencyMs(1L)
                    .build();
        });

        LlmMemoryEvalReport report = runner.run(cases, List.of(
                LlmMemoryEvalMode.BASELINE_NO_MEMORY,
                LlmMemoryEvalMode.VORTEX_MEMORY,
                LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));

        assertThat(report.getTotalCases()).isEqualTo(expectedTotalCases);
        assertThat(report.getTotalRuns()).isEqualTo(expectedTotalCases * 3);
        assertThat(report.getResults()).hasSize(expectedTotalCases * 3);
        assertThat(report.getModeSummaries()).satisfies(summaries -> {
            assertThat(summaries.get("Baseline-NoMemory")).satisfies(summary -> {
                assertThat(summary.getTotal()).isEqualTo(expectedTotalCases);
                assertThat(summary.getCorrect()).isZero();
                assertThat(summary.getAccuracy()).isEqualTo(0.0d);
                assertThat(summary.getFeedbackSubmitted()).isZero();
            });
            assertThat(summaries.get("Vortex-Memory")).satisfies(summary -> {
                assertThat(summary.getTotal()).isEqualTo(expectedTotalCases);
                assertThat(summary.getCorrect()).isEqualTo(expectedTotalCases);
                assertThat(summary.getAccuracy()).isEqualTo(1.0d);
                assertThat(summary.getRecallHitRate()).isEqualTo(1.0d);
                assertThat(summary.getFeedbackSubmitted()).isEqualTo(expectedTotalCases);
            });
            assertThat(summaries.get("Vortex-RecoveredMemory")).satisfies(summary -> {
                assertThat(summary.getTotal()).isEqualTo(expectedTotalCases);
                assertThat(summary.getCorrect()).isEqualTo(expectedTotalCases);
                assertThat(summary.getAccuracy()).isEqualTo(1.0d);
                assertThat(summary.getRecoveredRuns()).isEqualTo(expectedTotalCases);
                assertThat(summary.getRecoveredAccuracy()).isEqualTo(1.0d);
                assertThat(summary.getRecoveredL2HitRate()).isEqualTo(1.0d);
                assertThat(summary.getFeedbackSubmitted()).isEqualTo(expectedTotalCases);
            });
        });
        assertThat(report.getResults().stream()
                .filter(result -> "Baseline-NoMemory".equals(result.getMode())))
                .allSatisfy(result -> {
                    assertThat(result.isCorrect()).isFalse();
                    assertThat(result.isRecallHit()).isFalse();
                    assertThat(result.getFailureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_INSUFFICIENT_ANSWER);
                });
        assertThat(report.getResults().stream()
                .filter(result -> "Vortex-RecoveredMemory".equals(result.getMode())))
                .allSatisfy(result -> {
                    assertThat(result.isCorrect()).isTrue();
                    assertThat(result.getEvictedBeforeAnswer()).isTrue();
                    assertThat(result.getRecalledFromTiers()).contains("L2");
                });
    }

    @Test
    void runConfiguredModesShouldUseConfiguredDatasetLocation(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path datasetPath = tempDir.resolve("custom-eval-set.json");
        Files.writeString(datasetPath, """
                [
                  {
                    "caseId": "profile-013",
                    "namespace": "llm-eval-custom",
                    "memoryFragments": [
                      {
                        "fragmentId": "report-format",
                        "content": "Quarterly finance exports must be generated in CSV format.",
                        "tags": ["reporting", "format"]
                      }
                    ],
                    "question": "Which format must quarterly finance exports use?",
                    "expectedAnswer": "CSV",
                    "expectedFragments": ["report-format"],
                    "tags": ["reporting"],
                    "difficulty": "easy"
                  }
                ]
                """);
        properties.setDatasetLocation(datasetPath.toUri().toString());
        properties.setModes(List.of(LlmMemoryEvalMode.VORTEX_MEMORY));

        LlmMemoryEvalReport report = runner.runConfiguredModes();

        assertThat(report.getTotalCases()).isEqualTo(1);
        assertThat(report.getTotalRuns()).isEqualTo(1);
        assertThat(report.getResults()).singleElement().satisfies(result -> {
            assertThat(result.getCaseId()).isEqualTo("profile-013");
            assertThat(result.getMode()).isEqualTo("Vortex-Memory");
            assertThat(result.isCorrect()).isTrue();
        });
    }

    @Test
    void runRecoveredMemoryModeShouldReportEvictionAndL2Recovery() {
        LlmMemoryEvalCase evalCase = runner.loadDefaultCaseSet().getFirst();

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));

        assertThat(report.getTotalRuns()).isEqualTo(1);
        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.isCorrect()).isTrue();
        assertThat(result.getEvictedBeforeAnswer()).isTrue();
        assertThat(result.getRecalledFromTiers()).contains("L2");
        assertThat(result.getReturnedFragmentIds()).contains("favorite-language");
        assertThat(result.getReturnedFragmentIds()).allMatch(fragmentId -> !fragmentId.contains("eviction-filler-"));
        assertThat(result.getRecoveryFillerFragmentsInserted()).isPositive();
        assertThat(result.getRecoveryForceLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getRecallLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getRecallDiagnostics()).isNotNull();
        assertThat(result.getRecallDiagnostics().getFinalReturnedCount()).isEqualTo(result.getReturnedFragmentIds().size());
        assertThat(result.getRecallDiagnostics().getL2SearchAcceptedCount()).isPositive();
        assertThat(report.getModeSummaries().get("Vortex-RecoveredMemory").getRecoveredRuns()).isEqualTo(1);
        assertThat(report.getModeSummaries().get("Vortex-RecoveredMemory").getRecoveredAccuracy()).isEqualTo(1.0d);
    }

    @Test
    void runMemoryModeShouldSubmitFeedbackAndCaptureLearningDelta() {
        LlmMemoryEvalCase evalCase = runner.loadDefaultCaseSet().getFirst();

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.getFeedbackSubmitted()).isTrue();
        assertThat(result.getFeedbackUsedFragmentIds()).containsExactlyElementsOf(evalCase.getExpectedFragments());
        assertThat(result.getLearningSampleCountBefore()).isZero();
        assertThat(result.getLearningSampleCountAfter()).isEqualTo(1L);
        assertThat(result.getLearningActiveUpdateCountBefore()).isZero();
        assertThat(result.getLearningActiveUpdateCountAfter()).isEqualTo(1L);
        assertThat(result.getStoreLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getGenerationLatencyMs()).isEqualTo(31L);
        assertThat(result.getGenerationRequestBuildLatencyMs()).isEqualTo(3L);
        assertThat(result.getGenerationHttpRoundTripLatencyMs()).isEqualTo(23L);
        assertThat(result.getGenerationResponseParseLatencyMs()).isEqualTo(5L);
        assertThat(result.getGenerationRetryBackoffLatencyMs()).isZero();
        assertThat(result.getGenerationLatencyNanos()).isEqualTo(31_000_000L);
        assertThat(result.getGenerationRequestBuildLatencyNanos()).isEqualTo(3_400_000L);
        assertThat(result.getGenerationRequestSerializationLatencyNanos()).isEqualTo(1_200_000L);
        assertThat(result.getGenerationHttpRequestBuildLatencyNanos()).isEqualTo(2_200_000L);
        assertThat(result.getGenerationHttpRoundTripLatencyNanos()).isEqualTo(23_700_000L);
        assertThat(result.getGenerationResponseParseLatencyNanos()).isEqualTo(5_600_000L);
        assertThat(result.getGenerationResponseDecodeLatencyNanos()).isEqualTo(1_300_000L);
        assertThat(result.getGenerationResponseJsonParseLatencyNanos()).isEqualTo(4_300_000L);
        assertThat(result.getGenerationAttemptCount()).isEqualTo(1);
        assertThat(result.getGenerationHttpStatusCode()).isEqualTo(200);
        assertThat(result.getGenerationRequestBytes()).isEqualTo(321);
        assertThat(result.getGenerationResponseBytes()).isEqualTo(654);
        assertThat(result.getFeedbackLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(report.getModeSummaries().get("Vortex-Memory").getFeedbackSubmitted()).isEqualTo(1);
        assertThat(report.getModeSummaries().get("Vortex-Memory").getLearningUpdateCountDelta()).isEqualTo(1L);
    }

    @Test
    void runMemoryModeShouldClassifyRecallMissEvenWhenAnswerWasGuessed() {
        LlmMemoryEvalCase evalCase = LlmMemoryEvalCase.builder()
                .caseId("judge-001")
                .namespace("llm-eval-judge")
                .memoryFragments(List.of(
                        LlmMemoryEvalCase.EvalMemoryFragment.builder()
                                .fragmentId("actual-owner")
                                .content("The review owner is Mira.")
                                .tags(List.of("judge"))
                                .build(),
                        LlmMemoryEvalCase.EvalMemoryFragment.builder()
                                .fragmentId("hidden-policy")
                                .content("The review owner must also be confirmed by the hidden policy.")
                                .tags(List.of("hidden"))
                                .build()))
                .question("Who owns the review?")
                .expectedAnswer("Mira")
                .expectedFragments(List.of("actual-owner", "hidden-policy"))
                .tags(List.of("judge"))
                .build();
        expectedAnswers.put("judge-001", "Mira");

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.isRecallHit()).isTrue();
        assertThat(result.isCorrect()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(LlmMemoryEvalRunner.FAILURE_RECALL_MISS);
        assertThat(result.getMissingMustContain()).isEmpty();
        assertThat(result.getMatchedForbiddenTerms()).isEmpty();
    }

    @Test
    void runMemoryModeShouldClassifyInsufficientAnswerWhenExpectedMemoryWasReturned() {
        LlmMemoryEvalCase evalCase = LlmMemoryEvalCase.builder()
                .caseId("judge-002")
                .namespace("llm-eval-judge")
                .memoryFragments(List.of(LlmMemoryEvalCase.EvalMemoryFragment.builder()
                        .fragmentId("warehouse")
                        .content("The Zephyr risk dashboard uses Redshift.")
                        .tags(List.of("judge"))
                        .build()))
                .question("Which warehouse does Zephyr use?")
                .expectedAnswer("Redshift")
                .mustContain(List.of("Zephyr risk dashboard"))
                .expectedFragments(List.of("warehouse"))
                .tags(List.of("judge"))
                .build();
        when(generationServiceProvider.getIfAvailable()).thenReturn(request -> GenerationResult.builder()
                .content("The provided memory is insufficient to determine the warehouse.")
                .latencyMs(1L)
                .build());

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.isRecallHit()).isTrue();
        assertThat(result.isCorrect()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(LlmMemoryEvalRunner.FAILURE_INSUFFICIENT_WHEN_MEMORY_AVAILABLE);
        assertThat(result.getMissingMustContain()).containsExactly("Zephyr risk dashboard");
    }

    @Test
    void runMemoryModeShouldCaptureGenerationRuntimeSubtypeAndTelemetry() {
        LlmMemoryEvalCase evalCase = LlmMemoryEvalCase.builder()
                .caseId("judge-003")
                .namespace("llm-eval-judge")
                .memoryFragments(List.of(LlmMemoryEvalCase.EvalMemoryFragment.builder()
                        .fragmentId("warehouse")
                        .content("The Zephyr risk dashboard uses Redshift.")
                        .tags(List.of("judge"))
                        .build()))
                .question("Which warehouse does Zephyr use?")
                .expectedAnswer("Redshift")
                .expectedFragments(List.of("warehouse"))
                .tags(List.of("judge"))
                .build();
        when(generationServiceProvider.getIfAvailable()).thenReturn(request -> {
            GenerationLatencyBreakdown breakdown = GenerationLatencyBreakdown.builder()
                    .totalLatencyMs(61L)
                    .totalLatencyNanos(61_000_000L)
                    .httpRoundTripLatencyMs(60L)
                    .httpRoundTripLatencyNanos(60_000_000L)
                    .retryBackoffLatencyMs(1L)
                    .retryBackoffLatencyNanos(1_000_000L)
                    .attemptCount(2)
                    .build();
            throw new GenerationException(
                    "Generation request failed: request timed out",
                    null,
                    "generation_timeout",
                    true,
                    breakdown);
        });

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.isCorrect()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(LlmMemoryEvalRunner.FAILURE_RUNTIME_ERROR);
        assertThat(result.getRuntimeErrorType()).isEqualTo("generation_timeout");
        assertThat(result.getTransientRuntimeError()).isTrue();
        assertThat(result.getGenerationAttemptCount()).isEqualTo(2);
        assertThat(result.getGenerationRetryBackoffLatencyNanos()).isEqualTo(1_000_000L);
        assertThat(result.getGenerationHttpRoundTripLatencyMs()).isEqualTo(60L);
        assertThat(result.getErrorMessage()).contains("request timed out");
    }

    @Test
    void runMemoryModeShouldWaitForExpectedFragmentsToReachL2BeforeRecall() {
        properties.setRecoveryPollTimeout(Duration.ofMillis(200));
        properties.setRecoveryPollInterval(Duration.ofMillis(1));
        l2VisibilityDelaysByIdPart.put("audit-vault-region", new AtomicInteger(2));
        assertDelayedL2VisibleOnRecall.set(true);
        LlmMemoryEvalCase evalCase = runner.loadCaseSet("classpath:llm-memory-eval-set-v2.json").stream()
                .filter(candidate -> "v2-007".equals(candidate.getCaseId()))
                .findFirst()
                .orElseThrow();

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.isCorrect()).isTrue();
        assertThat(result.getRecoveryTargetWaitPollCount()).isGreaterThan(0);
        assertThat(l2VisibilityDelaysByIdPart.get("audit-vault-region").get()).isZero();
    }

    @Test
    void runMemoryModeShouldIgnoreL2RecoveryTargetsFromWrongNamespace() {
        properties.setRecoveryPollTimeout(Duration.ofMillis(200));
        properties.setRecoveryPollInterval(Duration.ofMillis(1));
        l2WrongNamespaceReadsByIdPart.put("audit-vault-region", new AtomicInteger(2));
        LlmMemoryEvalCase evalCase = runner.loadCaseSet("classpath:llm-memory-eval-set-v2.json").stream()
                .filter(candidate -> "v2-007".equals(candidate.getCaseId()))
                .findFirst()
                .orElseThrow();

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.isCorrect()).isTrue();
        assertThat(result.getRecoveryTargetWaitPollCount()).isGreaterThan(0);
        assertThat(l2WrongNamespaceReadsByIdPart.get("audit-vault-region").get()).isZero();
    }

    @Test
    void runRecoveredMemoryModeShouldFailWhenEvictionNeverHappens() {
        evictRecoveredTargetsOnFiller.set(false);
        properties.setRecoveryPollTimeout(Duration.ofMillis(30));
        properties.setRecoveryPollInterval(Duration.ofMillis(5));
        properties.setEvictionFillerFragments(6);
        LlmMemoryEvalCase evalCase = runner.loadDefaultCaseSet().getFirst();

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.isCorrect()).isFalse();
        assertThat(result.getEvictedBeforeAnswer()).isFalse();
        assertThat(result.getErrorMessage()).contains("Failed to force recovery eviction before answer");
        assertThat(result.getRecoveryForceLatencyMs()).isLessThan(200L);
        assertThat(result.getRecoveryFillerFragmentsInserted()).isPositive();
        assertThat(result.getRecoveryForcePollCount()).isGreaterThanOrEqualTo(0);
        assertThat(result.getGeneratedAnswer()).isEmpty();
    }

    @Test
    void runRecoveredMemoryModeShouldKeepAddingFillersBeyondInitialPlanUntilEvictionHappens() {
        requiredFillerInsertionsBeforeEviction.set(8);
        properties.setRecoveryPollTimeout(Duration.ofMillis(200));
        properties.setRecoveryPollInterval(Duration.ofMillis(5));
        properties.setEvictionFillerFragments(6);
        LlmMemoryEvalCase evalCase = runner.loadDefaultCaseSet().getFirst();

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        assertThat(result.isCorrect()).isTrue();
        assertThat(result.getEvictedBeforeAnswer()).isTrue();
        assertThat(result.getRecoveryFillerFragmentsInserted()).isGreaterThan(properties.getEvictionFillerFragments());
        assertThat(observedRecoveryFillerInsertions.get()).isGreaterThan(properties.getEvictionFillerFragments());
    }

    @Test
    void runRecoveredMemoryModeShouldEscalateFillerSizeWhenShortTargetsResistEviction() {
        minimumFillerTokensBeforeEviction.set(32);
        properties.setRecoveryPollTimeout(Duration.ofMillis(200));
        properties.setRecoveryPollInterval(Duration.ofMillis(5));
        properties.setEvictionFillerFragments(6);
        LlmMemoryEvalCase evalCase = runner.loadCaseSet("classpath:llm-memory-eval-set-v2.json").stream()
                .filter(candidate -> "v2-005".equals(candidate.getCaseId()))
                .findFirst()
                .orElseThrow();

        LlmMemoryEvalReport report = runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));

        LlmMemoryEvalResult result = report.getResults().getFirst();
        int maxFillerTokens = storedFragments.stream()
                .filter(fragment -> fragment.getId().contains("eviction-filler-"))
                .mapToInt(MemoryFragment::getTokenCount)
                .max()
                .orElseThrow();
        assertThat(result.isCorrect()).isTrue();
        assertThat(result.getEvictedBeforeAnswer()).isTrue();
        assertThat(result.getRecoveryFillerFragmentsInserted()).isGreaterThan(properties.getEvictionFillerFragments());
        assertThat(maxFillerTokens).isGreaterThanOrEqualTo(minimumFillerTokensBeforeEviction.get());
    }

    @Test
    void runRecoveredMemoryModeShouldAssignDistinctReasoningChainsToEvictionFillers() {
        requiredFillerInsertionsBeforeEviction.set(8);
        properties.setRecoveryPollTimeout(Duration.ofMillis(200));
        properties.setRecoveryPollInterval(Duration.ofMillis(5));
        properties.setEvictionFillerFragments(6);
        LlmMemoryEvalCase evalCase = runner.loadDefaultCaseSet().getFirst();

        runner.run(List.of(evalCase), List.of(LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));

        List<String> fillerChains = storedFragments.stream()
                .filter(fragment -> fragment.getId().contains("eviction-filler-"))
                .map(MemoryFragment::getReasoningChainId)
                .toList();
        assertThat(fillerChains).isNotEmpty();
        assertThat(fillerChains).doesNotHaveDuplicates();
    }

    @Test
    void runShouldIsolateNamespacesPerCaseWithinSameRun() {
        List<LlmMemoryEvalCase> cases = runner.loadDefaultCaseSet().subList(0, 2);

        runner.run(cases, List.of(LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));

        List<String> nonFillerNamespaces = storedFragments.stream()
                .filter(fragment -> !fragment.getId().contains("eviction-filler-"))
                .map(MemoryFragment::getNamespace)
                .distinct()
                .toList();

        assertThat(nonFillerNamespaces).hasSize(2);
        assertThat(nonFillerNamespaces).anyMatch(namespace -> namespace.contains("profile-001"));
        assertThat(nonFillerNamespaces).anyMatch(namespace -> namespace.contains("profile-002"));
    }

    @Test
    void runShouldScopeStoredFragmentIdsByModeWithinSameRun() {
        LlmMemoryEvalCase evalCase = runner.loadDefaultCaseSet().getFirst();

        runner.run(List.of(evalCase), List.of(
                LlmMemoryEvalMode.VORTEX_MEMORY,
                LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));

        List<String> memoryModeIds = storedFragments.stream()
                .map(MemoryFragment::getId)
                .filter(fragmentId -> fragmentId.contains("::vortex_memory::"))
                .toList();
        List<String> recoveredModeIds = storedFragments.stream()
                .map(MemoryFragment::getId)
                .filter(fragmentId -> fragmentId.contains("::vortex_recovered_memory::"))
                .filter(fragmentId -> !fragmentId.contains("eviction-filler-"))
                .toList();

        assertThat(memoryModeIds).isNotEmpty();
        assertThat(recoveredModeIds).isNotEmpty();
        assertThat(memoryModeIds).doesNotContainAnyElementsOf(recoveredModeIds);
    }

    @Test
    void runShouldEvaluateCasesConcurrentlyWithinEachModeWhenParallelismIsConfigured() {
        properties.setParallelism(4);
        CountDownLatch allRequestsInFlight = new CountDownLatch(4);
        AtomicInteger activeRequests = new AtomicInteger();
        AtomicInteger maxActiveRequests = new AtomicInteger();
        when(generationServiceProvider.getIfAvailable()).thenReturn(request -> {
            int active = activeRequests.incrementAndGet();
            maxActiveRequests.accumulateAndGet(active, Math::max);
            allRequestsInFlight.countDown();
            try {
                assertThat(allRequestsInFlight.await(1, TimeUnit.SECONDS)).isTrue();
                return GenerationResult.builder()
                        .content("memory insufficient")
                        .latencyMs(1L)
                        .build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for test requests", e);
            } finally {
                activeRequests.decrementAndGet();
            }
        });
        List<LlmMemoryEvalCase> cases = List.of(
                insufficientCase("parallel-001"),
                insufficientCase("parallel-002"),
                insufficientCase("parallel-003"),
                insufficientCase("parallel-004"));

        LlmMemoryEvalReport report = runner.run(cases, List.of(LlmMemoryEvalMode.BASELINE_NO_MEMORY));

        assertThat(maxActiveRequests.get()).isGreaterThan(1);
        assertThat(report.getRuntimeTelemetry()).satisfies(telemetry -> {
            assertThat(telemetry.getConfiguredParallelism()).isEqualTo(4);
            assertThat(telemetry.getActualWorkerCount()).isEqualTo(4);
            assertThat(telemetry.isModePhasedParallel()).isTrue();
            assertThat(telemetry.getTotalElapsedMs()).isGreaterThanOrEqualTo(0L);
            assertThat(telemetry.getModePhaseTimings()).singleElement().satisfies(phase -> {
                assertThat(phase.getModeIndex()).isZero();
                assertThat(phase.getMode()).isEqualTo("Baseline-NoMemory");
                assertThat(phase.getCaseCount()).isEqualTo(4);
                assertThat(phase.getElapsedMs()).isGreaterThanOrEqualTo(0L);
            });
        });
        assertThat(report.getResults())
                .extracting(LlmMemoryEvalResult::getCaseId)
                .containsExactly("parallel-001", "parallel-002", "parallel-003", "parallel-004");
        assertThat(report.getResults()).allSatisfy(result -> {
            assertThat(result.getMode()).isEqualTo("Baseline-NoMemory");
            assertThat(result.getGeneratedAnswer()).isEqualTo("memory insufficient");
            assertThat(result.getFailureReason()).isEqualTo(RuleBasedAnswerJudge.FAILURE_INSUFFICIENT_ANSWER);
        });
    }

    @Test
    void runShouldPreserveSerialResultOrderingWhenModePhasedParallelismIsConfigured() {
        properties.setParallelism(3);
        List<LlmMemoryEvalCase> cases = List.of(
                insufficientCase("parallel-order-001"),
                insufficientCase("parallel-order-002"),
                insufficientCase("parallel-order-003"));
        when(generationServiceProvider.getIfAvailable()).thenReturn(request -> GenerationResult.builder()
                .content("memory insufficient")
                .latencyMs(1L)
                .build());

        LlmMemoryEvalReport report = runner.run(cases, List.of(
                LlmMemoryEvalMode.BASELINE_NO_MEMORY,
                LlmMemoryEvalMode.BASELINE_NO_MEMORY));

        assertThat(report.getResults())
                .extracting(result -> result.getCaseId() + "/" + result.getMode())
                .containsExactly(
                        "parallel-order-001/Baseline-NoMemory",
                        "parallel-order-001/Baseline-NoMemory",
                        "parallel-order-002/Baseline-NoMemory",
                        "parallel-order-002/Baseline-NoMemory",
                        "parallel-order-003/Baseline-NoMemory",
                        "parallel-order-003/Baseline-NoMemory");
        assertThat(report.getRuntimeTelemetry().getModePhaseTimings())
                .extracting(LlmMemoryEvalReport.ModePhaseTiming::getModeIndex)
                .containsExactly(0, 1);
    }

    @Test
    void planRecoveryFillersShouldShrinkLargeDefaultFillersForShortTargets() {
        String namespace = "llm-eval-profile-vortex_recovered_memory-test";
        MemoryFragment target = MemoryFragment.builder()
                .id("profile-001::favorite-language::test")
                .namespace(namespace)
                .content("Avery's favorite programming language is Rust.")
                .tokenCount(14)
                .importance(0.575)
                .build();
        l1HotStore.put(target);
        l2FragmentsById.put(target.getId(), target);
        properties.setEvictionFillerFragments(6);
        properties.setEvictionFillerTokens(0);

        LlmMemoryEvalRunner.RecoveryFillerPlan plan = runner.planRecoveryFillers(namespace, List.of(target.getId()));

        assertThat(plan.fillerTokens()).isEqualTo(14);
        assertThat(plan.maxFillers()).isEqualTo(6);
    }

    @Test
    void planRecoveryFillersShouldExpandCountWhenShortTargetsNeedMorePressure() {
        String namespace = "llm-eval-profile-vortex_recovered_memory-test";
        MemoryFragment target = MemoryFragment.builder()
                .id("tiny-target")
                .namespace(namespace)
                .content("short target")
                .tokenCount(8)
                .importance(0.575)
                .build();
        l1HotStore.put(target);
        l2FragmentsById.put(target.getId(), target);
        properties.setEvictionFillerFragments(6);
        properties.setEvictionFillerTokens(0);

        LlmMemoryEvalRunner.RecoveryFillerPlan plan = runner.planRecoveryFillers(namespace, List.of(target.getId()));

        assertThat(plan.fillerTokens()).isEqualTo(8);
        assertThat(plan.maxFillers()).isEqualTo(12);
    }

    private String deterministicPassingAnswer(LlmMemoryEvalCase evalCase) {
        List<String> anchors = evalCase.getMustContain() == null ? List.of() : evalCase.getMustContain();
        return (evalCase.getExpectedAnswer() + " " + String.join(" ", anchors)).trim();
    }

    private Map<String, String> loadExpectedAnswers() {
        Map<String, String> answers = new LinkedHashMap<>();
        try {
            List<String> datasets = List.of(
                    "classpath:llm-memory-eval-set.json",
                    "classpath:llm-memory-eval-set-v2.json",
                    "classpath:llm-memory-eval-set-v3-real-agent-workload.json",
                    "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json");
            for (String dataset : datasets) {
                for (LlmMemoryEvalCase evalCase : runner.loadCaseSet(dataset)) {
                    answers.put(evalCase.getCaseId(), evalCase.getExpectedAnswer());
                }
            }
            return answers;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load expected answers for eval test", e);
        }
    }

    private LlmMemoryEvalCase insufficientCase(String caseId) {
        return LlmMemoryEvalCase.builder()
                .caseId(caseId)
                .namespace("llm-eval-parallel")
                .memoryFragments(List.of(LlmMemoryEvalCase.EvalMemoryFragment.builder()
                        .fragmentId("placeholder")
                        .content("Placeholder memory for validation only.")
                        .tags(List.of("parallel"))
                        .build()))
                .question("What does memory say?")
                .expectedAnswer("memory insufficient")
                .expectedFragments(List.of("placeholder"))
                .tags(List.of("parallel"))
                .build();
    }

    private AdaptiveWeightLearner.LearningSnapshot learningSnapshot(long sampleCount, long updateCount) {
        AdaptiveWeightProfile active = AdaptiveWeightProfile.builder()
                .profileName("chat-active")
                .alpha(0.3)
                .beta(0.5)
                .gamma(0.2)
                .updateCount(updateCount)
                .build();
        AdaptiveWeightProfile shadow = AdaptiveWeightProfile.builder()
                .profileName("chat-shadow")
                .alpha(0.3)
                .beta(0.5)
                .gamma(0.2)
                .updateCount(updateCount)
                .build();
        ShadowEvaluationTracker.ShadowEvaluationSnapshot shadowEvaluation =
                new ShadowEvaluationTracker.ShadowEvaluationSnapshot(
                        0.0, 0.0, 0.0,
                        0.0, 0.0, 0.0,
                        0.0, 0.0, 0.0,
                        0.0, 0.0,
                        0.0, 0.0,
                        sampleCount,
                        false,
                        null);
        return new AdaptiveWeightLearner.LearningSnapshot(active, shadow, shadowEvaluation, 0, null);
    }

    private boolean fragmentMatchesTags(MemoryFragment fragment, List<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) {
            return true;
        }
        List<String> fragmentTags = fragment.getTags();
        return fragmentTags != null && fragmentTags.containsAll(requiredTags);
    }

    private static final class TestL1HotStore implements L1HotStore {

        private final Map<String, MemoryFragment> fragmentsById = new ConcurrentHashMap<>();
        private final long maxTokenCapacity;

        private TestL1HotStore(long maxTokenCapacity) {
            this.maxTokenCapacity = maxTokenCapacity;
        }

        @Override
        public void put(MemoryFragment fragment) {
            fragmentsById.put(fragment.getId(), fragment);
        }

        @Override
        public Optional<MemoryFragment> get(String id) {
            return Optional.ofNullable(fragmentsById.get(id));
        }

        @Override
        public Optional<MemoryFragment> peek(String id) {
            return get(id);
        }

        @Override
        public List<MemoryFragment> getAll(String namespace) {
            return fragmentsById.values().stream()
                    .filter(fragment -> namespace.equals(fragment.getNamespace()))
                    .toList();
        }

        @Override
        public void remove(String id) {
            fragmentsById.remove(id);
        }

        @Override
        public long currentTokenCount() {
            return fragmentsById.values().stream().mapToLong(MemoryFragment::getTokenCount).sum();
        }

        @Override
        public long maxTokenCapacity() {
            return maxTokenCapacity;
        }

        @Override
        public void clear(String namespace) {
            fragmentsById.entrySet().removeIf(entry -> namespace.equals(entry.getValue().getNamespace()));
        }

        private void clearAll() {
            fragmentsById.clear();
        }

        private void evictRecoveredTargets() {
            fragmentsById.entrySet().removeIf(entry -> !entry.getKey().contains("eviction-filler-"));
        }
    }
}
