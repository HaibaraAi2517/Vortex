package com.vortex.app.integration;

import com.vortex.app.VortexApplication;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.MemoryFragment;
import com.vortex.app.integration.support.IsolatedIntegrationTestSupport;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.hmc.AdaptiveWeightLearner;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.snapshot.SnapshotService;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.l1.CaffeineHotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Default compose-backed integration coverage.
 * Maven verify automatically brings the compose dependencies up, runs these
 * tests, then tears the stack down again.
 */
@SpringBootTest(
        classes = {VortexApplication.class, DockerComposeIT.TestEmbeddingConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true"
        })
@AutoConfigureObservability
@Import(IsolatedIntegrationTestSupport.Config.class)
@ContextConfiguration(initializers = IsolatedIntegrationTestSupport.Initializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class DockerComposeIT {

    private static final String TEST_NAMESPACE_PREFIX = "dkit-";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("vortex.storage.l1.max-tokens", () -> 24);
        registry.add("vortex.storage.l3.minio.endpoint", () -> IsolatedIntegrationTestSupport.property(
                "vortex.it.minio-endpoint", "http://localhost:9000"));
        registry.add("vortex.storage.l3.minio.access-key", () -> IsolatedIntegrationTestSupport.property(
                "vortex.it.minio-access-key", "minioadmin"));
        registry.add("vortex.storage.l3.minio.secret-key", () -> IsolatedIntegrationTestSupport.property(
                "vortex.it.minio-secret-key", "minioadmin"));
        registry.add("vortex.storage.l2.milvus.host", () -> IsolatedIntegrationTestSupport.property(
                "vortex.it.milvus-host", "localhost"));
        registry.add("vortex.storage.l2.milvus.port", () -> IsolatedIntegrationTestSupport.intProperty(
                "vortex.it.milvus-port", 19530));
        registry.add("vortex.storage.l2.embedding-dim", () -> 4);
        registry.add("vortex.kernel.embedding.bge.model-path", () -> "unused-in-it");
        registry.add("vortex.kernel.splitter.max-tokens-per-chunk", () -> 512);
        registry.add("vortex.kernel.namespace-quota.hard-fraction", () -> 1.0);
        registry.add("vortex.kernel.namespace-quota.soft-fraction", () -> 1.0);
        registry.add("vortex.kernel.namespace-quota.min-hard-tokens", () -> 24);
        registry.add("vortex.kernel.snapshot.checkpoint.max-deltas-before-full", () -> 1);
        registry.add("vortex.kernel.learning.min-samples-before-promotion", () -> 1);
        registry.add("vortex.kernel.learning.shadow-promotion-window-days", () -> 0);
        registry.add("logging.level.com.vortex", () -> "INFO");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private L1HotStore l1HotStore;

    @Autowired
    private L2WarmStore l2WarmStore;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private AdaptiveWeightLearner adaptiveWeightLearner;

    @Autowired
    private HierarchicalMemoryController hmc;

    private String namespace;

    @BeforeEach
    void setUp() {
        clearIntegrationNamespaces();
        namespace = TEST_NAMESPACE_PREFIX + UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(adaptiveWeightLearner, "clearPendingSessionsForTest");
    }

    @AfterEach
    void tearDown() {
        clearIntegrationNamespaces();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(adaptiveWeightLearner, "clearPendingSessionsForTest");
    }

    @Test
    void memoryStoreRecallEvictCycle() {
        MemoryFragment coldFragment = fragment(
                "frag-java-thread-safety",
                "java thread safety synchronization locks",
                new float[]{1.0f, 0.0f, 0.0f, 0.0f}, 10, 0.1);
        MemoryFragment mediumFragment = fragment(
                "frag-java-concurrency",
                "java concurrency executors futures",
                new float[]{0.88f, 0.12f, 0.0f, 0.0f}, 10, 0.8);
        MemoryFragment fillerFragment = fragment(
                "frag-python-data",
                "python pandas dataframe joins",
                new float[]{0.0f, 1.0f, 0.0f, 0.0f}, 10, 0.8);

        storeFragment(coldFragment);
        storeFragment(mediumFragment);
        storeFragment(fillerFragment);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(l2WarmStore.get(coldFragment.getId())).isPresent());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(l1HotStore.peek(coldFragment.getId())).isEmpty();
            assertThat(l1HotStore.peek(mediumFragment.getId())).isPresent();
            assertThat(l1HotStore.peek(fillerFragment.getId())).isPresent();
        });

        ResponseEntity<RecallResult> recallResponse = restTemplate.postForEntity(
                "/api/v1/memory/recall",
                RecallQuery.builder()
                        .query("java thread safety locks")
                        .namespace(namespace)
                        .topK(3)
                        .tokenBudget(64)
                        .scenario(MemoryScenario.CHAT)
                        .build(),
                RecallResult.class);

        assertThat(recallResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        RecallResult recall = recallResponse.getBody();
        assertThat(recall).isNotNull();
        assertThat(recall.getFragments()).isNotEmpty();
        assertThat(recall.getFragments().stream().map(f -> f.getFragment().getId()))
                .contains(coldFragment.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(l1HotStore.peek(coldFragment.getId())).isPresent());

        ResponseEntity<Map> health = restTemplate.getForEntity("/api/v1/memory/health", Map.class);
        assertThat(health.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(health.getBody()).isNotNull();
        assertThat(health.getBody()).containsEntry("dictionaryVersion", "memory-health-v2");
        assertThat(((Number) health.getBody().get("l1TokensUsed")).longValue()).isLessThanOrEqualTo(24L);
    }

    @Test
    void observabilityEndpointsExposeAlignedHealthCatalogAndPrometheusMetadata() {
        ResponseEntity<Map> healthResponse = restTemplate.getForEntity("/api/v1/memory/health", Map.class);
        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthResponse.getBody()).isNotNull();
        assertThat(healthResponse.getBody()).containsEntry("dictionaryVersion", "memory-health-v2");
        assertThat(healthResponse.getBody()).containsKeys("summary", "statusReason", "details");

        Map<String, Object> healthDetails = (Map<String, Object>) healthResponse.getBody().get("details");
        assertThat(healthDetails).isNotNull();
        assertThat(healthDetails).containsKeys(
                "checkpointRecoverySuccessRate",
                "persistenceSuccessRate",
                "recoverySuccessRate",
                "learningEvaluationActive",
                "learningSampleCount",
                "summary",
                "dictionaryVersion");
        assertThat(healthDetails).containsEntry("learningEvaluationActive", false);
        assertThat(((Number) healthDetails.get("learningSampleCount")).longValue()).isZero();

        ResponseEntity<Map> catalogResponse = restTemplate.getForEntity("/api/v1/memory/health/catalog", Map.class);
        assertThat(catalogResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalogResponse.getBody()).isNotNull();
        assertThat(catalogResponse.getBody()).containsEntry("dictionaryVersion", "memory-health-v2");
        assertThat(catalogResponse.getBody()).containsEntry("migrationGuide", "ops/runbooks/memory-health-migration.md");

        List<Map<String, Object>> compatibility = (List<Map<String, Object>>) catalogResponse.getBody().get("compatibility");
        assertThat(compatibility).isNotNull();
        assertThat(compatibility)
                .extracting(item -> item.get("deprecatedKey"), item -> item.get("replacementKey"))
                .contains(
                        org.assertj.core.groups.Tuple.tuple("recovery_success_rate_low", "checkpoint_recovery_success_rate_low"),
                        org.assertj.core.groups.Tuple.tuple("recoverySuccessRate", "checkpointRecoverySuccessRate"),
                        org.assertj.core.groups.Tuple.tuple(
                                "vortex_hmc_slo_recovery_success_rate",
                                "vortex_hmc_slo_checkpoint_recovery_success_rate"));

        List<Map<String, Object>> signals = (List<Map<String, Object>>) catalogResponse.getBody().get("signals");
        assertThat(signals).isNotNull();
        assertThat(signals)
                .extracting(item -> item.get("code"))
                .contains("checkpoint_recovery_success_rate_low", "memory_persistence_success_rate_low");

        ResponseEntity<Map> actuatorResponse = restTemplate.getForEntity("/actuator", Map.class);
        assertThat(actuatorResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actuatorResponse.getBody()).isNotNull();
        Map<String, Object> actuatorLinks = (Map<String, Object>) actuatorResponse.getBody().get("_links");
        assertThat(actuatorLinks).isNotNull();
        assertThat(actuatorLinks).containsKeys("prometheus", "metrics", "health");

        ResponseEntity<String> prometheusResponse = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheusResponse.getBody()).contains(
                "vortex_hmc_slo_checkpoint_recovery_success_rate",
                "vortex_hmc_slo_persistence_success_rate",
                "vortex_hmc_slo_recovery_success_rate");
    }

    @Test
    void taskCheckpointRecoverCycle() {
        ResponseEntity<Map> createTask = restTemplate.postForEntity(
                "/api/v1/tasks",
                Map.of("description", "integration task", "namespace", namespace),
                Map.class);
        assertThat(createTask.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = (String) createTask.getBody().get("taskId");
        assertThat(taskId).isNotBlank();

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> appendNode = restTemplate.postForEntity(
                    "/api/v1/tasks/" + taskId + "/nodes",
                    Map.of("type", "THOUGHT", "content", "node-" + i),
                    Map.class);
            assertThat(appendNode.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map> checkpointResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/checkpoint", null, Map.class);
        assertThat(checkpointResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String checkpointId = (String) checkpointResponse.getBody().get("checkpointId");
        assertThat(checkpointId).isNotBlank();

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshotService, "evictFromCacheForTest", taskId);

        ResponseEntity<Map> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", checkpointId), Map.class);
        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recoverResponse.getBody()).containsEntry("taskId", taskId);

        ResponseEntity<String> recoveredDag = restTemplate.exchange(
                "/api/v1/tasks/" + taskId + "/dag",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(recoveredDag.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recoveredDag.getBody()).contains("node-0", "node-1", "node-2");
    }

    @Test
    void taskRecoverReplaysWalAcrossRepeatedRecovery() {
        ResponseEntity<Map> createTask = restTemplate.postForEntity(
                "/api/v1/tasks",
                Map.of("description", "repeat recovery task", "namespace", namespace),
                Map.class);
        assertThat(createTask.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = (String) createTask.getBody().get("taskId");
        assertThat(taskId).isNotBlank();

        ResponseEntity<DagNode> beforeCheckpointNode = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of("type", "THOUGHT", "content", "before-checkpoint"),
                DagNode.class);
        assertThat(beforeCheckpointNode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(beforeCheckpointNode.getBody()).isNotNull();

        ResponseEntity<Map> checkpointResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/checkpoint", null, Map.class);
        assertThat(checkpointResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String checkpointId = (String) checkpointResponse.getBody().get("checkpointId");
        assertThat(checkpointId).isNotBlank();

        ResponseEntity<DagNode> afterCheckpointNode = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of("type", "ACTION", "content", "after-checkpoint"),
                DagNode.class);
        assertThat(afterCheckpointNode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterCheckpointNode.getBody()).isNotNull();

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshotService, "evictFromCacheForTest", taskId);

        ResponseEntity<Map> firstRecover = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", checkpointId), Map.class);
        assertThat(firstRecover.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstRecover.getBody()).containsEntry("taskId", taskId);
        assertDagContains(taskId, "before-checkpoint", "after-checkpoint");

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshotService, "evictFromCacheForTest", taskId);

        ResponseEntity<Map> secondRecover = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", checkpointId), Map.class);
        assertThat(secondRecover.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondRecover.getBody()).containsEntry("taskId", taskId);
        assertDagContains(taskId, "before-checkpoint", "after-checkpoint");
    }

    @Test
    void taskCheckpointListingShowsFullDeltaAndRecoverableEntries() {
        ResponseEntity<Map> createTask = restTemplate.postForEntity(
                "/api/v1/tasks",
                Map.of("description", "checkpoint listing task", "namespace", namespace),
                Map.class);
        assertThat(createTask.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = (String) createTask.getBody().get("taskId");
        assertThat(taskId).isNotBlank();

        ResponseEntity<DagNode> firstNode = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of("type", "THOUGHT", "content", "before-full"),
                DagNode.class);
        assertThat(firstNode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstNode.getBody()).isNotNull();

        String firstCheckpointId = checkpoint(taskId);

        ResponseEntity<DagNode> secondNode = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of(
                        "type", "ACTION",
                        "content", "after-delta",
                        "targetNodeId", firstNode.getBody().getNodeId(),
                        "edgeType", "CONTROL_DEP"),
                DagNode.class);
        assertThat(secondNode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondNode.getBody()).isNotNull();

        String secondCheckpointId = checkpoint(taskId);

        ResponseEntity<DagNode> thirdNode = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of("type", "THOUGHT", "content", "after-full"),
                DagNode.class);
        assertThat(thirdNode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(thirdNode.getBody()).isNotNull();

        String thirdCheckpointId = checkpoint(taskId);

        ResponseEntity<CheckpointMetadata[]> checkpointsResponse = restTemplate.getForEntity(
                "/api/v1/tasks/" + taskId + "/checkpoints",
                CheckpointMetadata[].class);
        assertThat(checkpointsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        CheckpointMetadata[] checkpoints = checkpointsResponse.getBody();
        assertThat(checkpoints).isNotNull();
        assertThat(checkpoints)
                .extracting(CheckpointMetadata::getCheckpointId)
                .containsExactly(firstCheckpointId, secondCheckpointId, thirdCheckpointId);
        assertThat(checkpoints)
                .extracting(CheckpointMetadata::getType)
                .containsExactly(
                        CheckpointMetadata.CheckpointType.FULL,
                        CheckpointMetadata.CheckpointType.DELTA,
                        CheckpointMetadata.CheckpointType.FULL);
        assertThat(checkpoints[1].getBaseCheckpointId()).isEqualTo(firstCheckpointId);

        recoverAndAssertDag(taskId, firstCheckpointId, new String[]{"before-full"}, new String[]{"after-delta", "after-full"});
        recoverAndAssertDag(taskId, secondCheckpointId, new String[]{"before-full", "after-delta"}, new String[]{"after-full"});
        recoverAndAssertDag(taskId, thirdCheckpointId, new String[]{"before-full", "after-delta", "after-full"}, new String[0]);
    }

    @Test
    void feedbackDrivesWeightEvolution() {
        AdaptiveWeightLearner.LearningSnapshot before = adaptiveWeightLearner.snapshot(MemoryScenario.CHAT);
        long initialUpdates = before.active().getUpdateCount();

        for (int i = 0; i < 8; i++) {
            storeFragment(fragment("frag-learning-good-" + i,
                    "java locks volatile happens-before",
                    new float[]{1.0f, 0.0f, 0.0f, 0.0f}, 4, 0.5));
            storeFragment(fragment("frag-learning-alt-" + i,
                    "python notebooks visualization",
                    new float[]{0.0f, 1.0f, 0.0f, 0.0f}, 4, 0.5));

            ResponseEntity<RecallResult> recallResponse = restTemplate.postForEntity(
                    "/api/v1/memory/recall",
                    RecallQuery.builder()
                            .query("java locks volatile")
                            .namespace(namespace).topK(4).tokenBudget(64)
                            .scenario(MemoryScenario.CHAT).build(),
                    RecallResult.class);
            assertThat(recallResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            RecallResult recall = recallResponse.getBody();
            assertThat(recall).isNotNull();
            String usedFragmentId = recall.getFragments().stream()
                    .map(RecallResult.ScoredFragment::getFragment)
                    .map(MemoryFragment::getId)
                    .filter(id -> id.startsWith("frag-learning-good"))
                    .findFirst()
                    .orElse(recall.getFragments().getFirst().getFragment().getId());

            restTemplate.postForEntity("/api/v1/memory/feedback",
                    MemoryFeedbackRequest.builder()
                            .recallSessionId(recall.getRecallSessionId())
                            .usedFragmentIds(List.of(usedFragmentId))
                            .answerAccepted(true).build(),
                    Map.class);
        }

        AdaptiveWeightLearner.LearningSnapshot after = adaptiveWeightLearner.snapshot(MemoryScenario.CHAT);
        assertThat(after.pendingRecallSessions()).isZero();
        assertThat(after.active().getUpdateCount()).isGreaterThan(initialUpdates);
    }

    @Test
    void fullDemoStory_coversStoreRecallEvictCheckpointRecoverContinueAndFeedback() {
        double initialImportance = 0.10;
        MemoryFragment primary = fragment(
                "frag-demo-primary",
                "java checkpoint recover dag node",
                new float[]{1.0f, 0.0f, 1.0f, 0.0f},
                10,
                initialImportance);
        MemoryFragment secondary = fragment(
                "frag-demo-secondary",
                "java locks concurrency executor",
                new float[]{1.0f, 0.0f, 0.1f, 0.0f},
                10,
                0.80);
        MemoryFragment distractor = fragment(
                "frag-demo-distractor",
                "python notebook dataframe joins",
                new float[]{0.0f, 1.0f, 0.0f, 0.0f},
                10,
                0.80);

        storeFragment(primary);
        storeFragment(secondary);
        storeFragment(distractor);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(l2WarmStore.get(primary.getId())).isPresent());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(l1HotStore.peek(primary.getId())).isEmpty();
            assertThat(l1HotStore.peek(secondary.getId())).isPresent();
            assertThat(l1HotStore.peek(distractor.getId())).isPresent();
        });

        AdaptiveWeightLearner.LearningSnapshot learningBefore =
                adaptiveWeightLearner.snapshot(MemoryScenario.CHAT);

        ResponseEntity<RecallResult> recallResponse = restTemplate.postForEntity(
                "/api/v1/memory/recall",
                RecallQuery.builder()
                        .query("java checkpoint recover node")
                        .namespace(namespace)
                        .topK(3)
                        .tokenBudget(64)
                        .scenario(MemoryScenario.CHAT)
                        .build(),
                RecallResult.class);
        assertThat(recallResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        RecallResult recall = recallResponse.getBody();
        assertThat(recall).isNotNull();
        assertThat(recall.getRecallSessionId()).isNotBlank();
        assertThat(recall.getFragments()).isNotEmpty();
        RecallResult.ScoredFragment primaryRecall = recall.getFragments().stream()
                .filter(fragment -> primary.getId().equals(fragment.getFragment().getId()))
                .findFirst()
                .orElseThrow();
        assertThat(primaryRecall.getTier()).isEqualTo("L2");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            MemoryFragment refreshed = l1HotStore.peek(primary.getId()).orElseThrow();
            assertThat(refreshed.getImportance()).isGreaterThan(initialImportance);
        });

        ResponseEntity<Map> createTask = restTemplate.postForEntity(
                "/api/v1/tasks",
                Map.of("description", "compose full demo lifecycle", "namespace", namespace),
                Map.class);
        assertThat(createTask.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = (String) createTask.getBody().get("taskId");
        assertThat(taskId).isNotBlank();

        restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of("type", "THOUGHT", "content", "before-checkpoint"),
                Map.class);
        String checkpointId = checkpoint(taskId);

        restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of("type", "ACTION", "content", "after-checkpoint"),
                Map.class);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshotService, "evictFromCacheForTest", taskId);

        ResponseEntity<Map> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", checkpointId),
                Map.class);
        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recoverResponse.getBody()).containsEntry("taskId", taskId);
        assertDagContains(taskId, "before-checkpoint", "after-checkpoint");

        ResponseEntity<DagNode> continueAppend = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of("type", "OBSERVATION", "content", "after-recover"),
                DagNode.class);
        assertThat(continueAppend.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(continueAppend.getBody()).isNotNull();
        assertDagContains(taskId, "before-checkpoint", "after-checkpoint", "after-recover");

        ResponseEntity<Map> feedbackResponse = restTemplate.postForEntity(
                "/api/v1/memory/feedback",
                MemoryFeedbackRequest.builder()
                        .recallSessionId(recall.getRecallSessionId())
                        .usedFragmentIds(List.of(primaryRecall.getFragment().getId()))
                        .answerAccepted(true)
                        .build(),
                Map.class);
        assertThat(feedbackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(adaptiveWeightLearner.snapshot(MemoryScenario.CHAT).active().getUpdateCount())
                        .isGreaterThan(learningBefore.active().getUpdateCount()));
    }

    private void storeFragment(MemoryFragment fragment) {
        hmc.storeFragment(fragment);
    }

    private void assertDagContains(String taskId, String... contents) {
        ResponseEntity<String> dag = restTemplate.exchange(
                "/api/v1/tasks/" + taskId + "/dag",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(dag.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dag.getBody()).contains(contents);
    }

    private void recoverAndAssertDag(String taskId, String checkpointId, String[] expectedContents, String[] unexpectedContents) {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshotService, "evictFromCacheForTest", taskId);

        ResponseEntity<Map> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", checkpointId), Map.class);
        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recoverResponse.getBody()).containsEntry("taskId", taskId);

        ResponseEntity<String> dag = restTemplate.exchange(
                "/api/v1/tasks/" + taskId + "/dag",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(dag.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dag.getBody()).contains(expectedContents);
        if (unexpectedContents.length > 0) {
            assertThat(dag.getBody()).doesNotContain(unexpectedContents);
        }
    }

    private String checkpoint(String taskId) {
        ResponseEntity<Map> checkpointResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/checkpoint", null, Map.class);
        assertThat(checkpointResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String checkpointId = (String) checkpointResponse.getBody().get("checkpointId");
        assertThat(checkpointId).isNotBlank();
        return checkpointId;
    }

    private MemoryFragment fragment(String id, String content, float[] embedding, int tokenCount, double importance) {
        return MemoryFragment.builder()
                .id(id + "-" + namespace)
                .namespace(namespace)
                .content(content)
                .embedding(embedding)
                .tokenCount(tokenCount)
                .importance(importance)
                .tags(List.of("integration"))
                .build();
    }

    private void clearIntegrationNamespaces() {
        if (l1HotStore instanceof CaffeineHotStore caffeineHotStore) {
            Set<String> namespaces = caffeineHotStore.allFragments().stream()
                    .map(MemoryFragment::getNamespace)
                    .filter(ns -> ns != null && ns.startsWith(TEST_NAMESPACE_PREFIX))
                    .collect(Collectors.toSet());
            namespaces.forEach(l1HotStore::clear);
        }
        if (namespace != null && namespace.startsWith(TEST_NAMESPACE_PREFIX)) {
            l1HotStore.clear(namespace);
        }
    }

    @TestConfiguration
    static class TestEmbeddingConfig {

        @Bean("bgeSmallEmbeddingService")
        @Primary
        StubEmbeddingService stubEmbeddingService() {
            return new StubEmbeddingService();
        }

        static class StubEmbeddingService implements EmbeddingService, TokenCounter {

            @Override
            public float[] embed(String text) {
                float[] vector = semanticVector(text);
                normalize(vector);
                return vector;
            }

            @Override
            public int dimension() { return 4; }

            @Override
            public int countTokens(String text) {
                if (text == null || text.isBlank()) return 0;
                return text.trim().split("\\s+").length;
            }

            private float[] semanticVector(String text) {
                String n = text == null ? "" : text.toLowerCase();
                float[] v = new float[4];
                v[0] = score(n, Set.of("java", "thread", "safety", "lock", "locks", "volatile", "synchronization", "concurrency", "happens-before"));
                v[1] = score(n, Set.of("python", "pandas", "dataframe", "joins", "notebooks", "visualization"));
                v[2] = score(n, Set.of("task", "checkpoint", "recover", "dag", "node"));
                v[3] = Math.max(1.0f, countTokens(text));
                return v;
            }

            private float score(String normalized, Set<String> keywords) {
                float value = 0.5f;
                for (String keyword : keywords) {
                    if (normalized.contains(keyword)) value += 1.0f;
                }
                return value;
            }

            private void normalize(float[] vector) {
                double norm = 0.0;
                for (float v : vector) norm += v * v;
                norm = Math.sqrt(norm);
                if (norm == 0.0) return;
                for (int i = 0; i < vector.length; i++) vector[i] = (float) (vector[i] / norm);
            }
        }
    }
}

