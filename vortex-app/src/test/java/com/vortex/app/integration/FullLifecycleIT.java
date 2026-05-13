package com.vortex.app.integration;

import com.github.benmanes.caffeine.cache.Cache;
import com.vortex.app.VortexApplication;
import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.hmc.AdaptiveWeightLearner;
import com.vortex.kernel.snapshot.SnapshotService;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        classes = {VortexApplication.class, FullLifecycleIT.TestEmbeddingConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class FullLifecycleIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final String SHADOW_PATH = System.getProperty("java.io.tmpdir") + "/vortex-shadow-it.json";
    private static final String DLQ_PATH = System.getProperty("java.io.tmpdir") + "/vortex-dlq-it.jsonl";
    private static final String PROCESSED_KEYS_PATH = System.getProperty("java.io.tmpdir") + "/vortex-processed-it.txt";

    @Container
    static final GenericContainer<?> minio = new GenericContainer<>("minio/minio:RELEASE.2024-07-04T14-25-45Z")
            .withNetwork(NETWORK)
            .withNetworkAliases("minio")
            .withCommand("server /data --console-address :9001")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static final GenericContainer<?> etcd = new GenericContainer<>("quay.io/coreos/etcd:v3.5.5")
            .withNetwork(NETWORK)
            .withNetworkAliases("etcd")
            .withEnv("ETCD_AUTO_COMPACTION_MODE", "revision")
            .withEnv("ETCD_AUTO_COMPACTION_RETENTION", "1000")
            .withEnv("ETCD_QUOTA_BACKEND_BYTES", "4294967296")
            .withEnv("ETCD_SNAPSHOT_COUNT", "50000")
            .withCommand(
                    "etcd",
                    "--advertise-client-urls=http://etcd:2379",
                    "--listen-client-urls=http://0.0.0.0:2379",
                    "--data-dir=/etcd")
            .withExposedPorts(2379)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static final GenericContainer<?> milvus = new GenericContainer<>("milvusdb/milvus:v2.4.4")
            .withNetwork(NETWORK)
            .withNetworkAliases("milvus")
            .withEnv("ETCD_ENDPOINTS", "etcd:2379")
            .withEnv("MINIO_ADDRESS", "minio:9000")
            .withCommand("milvus", "run", "standalone")
            .withExposedPorts(19530, 9091)
            .waitingFor(Wait.forHttp("/healthz").forPort(9091).withStartupTimeout(Duration.ofMinutes(3)));

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

    private String namespace;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("vortex.storage.l1.max-tokens", () -> 24);
        registry.add("vortex.storage.l3.minio.endpoint", () -> "http://localhost:" + minio.getMappedPort(9000));
        registry.add("vortex.storage.l3.minio.access-key", () -> "minioadmin");
        registry.add("vortex.storage.l3.minio.secret-key", () -> "minioadmin");
        registry.add("vortex.storage.l3.minio.bucket", () -> "vortex-it");
        registry.add("vortex.storage.l2.milvus.host", () -> "localhost");
        registry.add("vortex.storage.l2.milvus.port", () -> milvus.getMappedPort(19530));
        registry.add("vortex.storage.l2.embedding-dim", () -> 4);
        registry.add("vortex.storage.l2.milvus.drop-collection-on-startup", () -> true);
        registry.add("vortex.storage.l2.milvus.drop-collection-confirm-token", () -> "I-KNOW-WHAT-I-AM-DOING");
        registry.add("vortex.kernel.embedding.bge.model-path", () -> "unused-in-it");
        registry.add("vortex.kernel.splitter.max-tokens-per-chunk", () -> 512);
        registry.add("vortex.kernel.learning.min-samples-before-promotion", () -> 1);
        registry.add("vortex.kernel.learning.shadow-promotion-window-days", () -> 0);
        registry.add("vortex.kernel.learning.shadow-persistence-path", () -> SHADOW_PATH);
        registry.add("vortex.kernel.persistence.dlq.path", () -> DLQ_PATH);
        registry.add("vortex.kernel.persistence.processed-keys.path", () -> PROCESSED_KEYS_PATH);
        registry.add("logging.level.com.vortex", () -> "INFO");
    }

    @BeforeEach
    void setUp() throws Exception {
        namespace = "demo-it-" + UUID.randomUUID();
        l1HotStore.clear(namespace);
        clearAdaptiveRecallSessions();
    }

    @AfterEach
    void tearDown() throws Exception {
        l1HotStore.clear(namespace);
        clearAdaptiveRecallSessions();
    }

    @Test
    void memoryStoreRecallEvictCycle() {
        MemoryFragment coldFragment = fragment(
                "frag-java-thread-safety",
                "java thread safety synchronization locks",
                new float[]{1.0f, 0.0f, 0.0f, 0.0f},
                10);
        MemoryFragment mediumFragment = fragment(
                "frag-java-concurrency",
                "java concurrency executors futures",
                new float[]{0.88f, 0.12f, 0.0f, 0.0f},
                10);
        MemoryFragment fillerFragment = fragment(
                "frag-python-data",
                "python pandas dataframe joins",
                new float[]{0.0f, 1.0f, 0.0f, 0.0f},
                10);

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
        assertThat(recall.getFragments().stream()
                .filter(f -> coldFragment.getId().equals(f.getFragment().getId()))
                .findFirst()
                .orElseThrow()
                .getTier())
                .isEqualTo("L2");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(l1HotStore.peek(coldFragment.getId())).isPresent());

        ResponseEntity<Map> health = restTemplate.getForEntity("/api/v1/memory/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) health.getBody().get("l1TokensUsed")).longValue()).isLessThanOrEqualTo(24L);
    }

    @Test
    void taskCheckpointRecoverCycle() throws Exception {
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
                "/api/v1/tasks/" + taskId + "/checkpoint",
                null,
                Map.class);
        assertThat(checkpointResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String checkpointId = (String) checkpointResponse.getBody().get("checkpointId");
        assertThat(checkpointId).isNotBlank();

        invalidateTaskCache(taskId);

        ResponseEntity<String> dagResponse = restTemplate.exchange(
                "/api/v1/tasks/" + taskId + "/dag",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);
        assertThat(dagResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dagResponse.getBody()).contains("node-0", "node-1", "node-2");

        ResponseEntity<Map> getTaskResponse = restTemplate.exchange(
                "/api/v1/tasks/" + taskId,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                Map.class);
        assertThat(getTaskResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getTaskResponse.getBody()).containsEntry("taskId", taskId);
        assertThat(getTaskResponse.getBody()).containsEntry("latestCheckpointId", checkpointId);

        ResponseEntity<Map> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", checkpointId),
                Map.class);
        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recoverResponse.getBody()).containsEntry("taskId", taskId);

        ResponseEntity<String> recoveredDag = restTemplate.exchange(
                "/api/v1/tasks/" + taskId + "/dag",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);
        assertThat(recoveredDag.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recoveredDag.getBody()).contains("node-0", "node-1", "node-2");
    }

    @Test
    void feedbackDrivesWeightEvolution() {
        AdaptiveWeightLearner.LearningSnapshot before = adaptiveWeightLearner.snapshot(MemoryScenario.CHAT);
        String initialActiveProfile = before.active().getProfileName();
        long initialUpdates = before.active().getUpdateCount();

        String preferredFragmentId = "frag-learning-good";
        String alternateFragmentId = "frag-learning-alt";

        for (int i = 0; i < 8; i++) {
            storeFragment(fragment(
                    preferredFragmentId + "-" + i,
                    "java locks volatile happens-before",
                    new float[]{1.0f, 0.0f, 0.0f, 0.0f},
                    4));
            storeFragment(fragment(
                    alternateFragmentId + "-" + i,
                    "python notebooks visualization",
                    new float[]{0.0f, 1.0f, 0.0f, 0.0f},
                    4));

            ResponseEntity<RecallResult> recallResponse = restTemplate.postForEntity(
                    "/api/v1/memory/recall",
                    RecallQuery.builder()
                            .query("java locks volatile")
                            .namespace(namespace)
                            .topK(4)
                            .tokenBudget(64)
                            .scenario(MemoryScenario.CHAT)
                            .build(),
                    RecallResult.class);
            assertThat(recallResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            RecallResult recall = recallResponse.getBody();
            assertThat(recall).isNotNull();
            String usedFragmentId = recall.getFragments().stream()
                    .map(RecallResult.ScoredFragment::getFragment)
                    .map(MemoryFragment::getId)
                    .filter(id -> id.startsWith(preferredFragmentId))
                    .findFirst()
                    .orElse(recall.getFragments().getFirst().getFragment().getId());

            ResponseEntity<Map> feedbackResponse = restTemplate.postForEntity(
                    "/api/v1/memory/feedback",
                    MemoryFeedbackRequest.builder()
                            .recallSessionId(recall.getRecallSessionId())
                            .usedFragmentIds(List.of(usedFragmentId))
                            .answerAccepted(true)
                            .build(),
                    Map.class);
            assertThat(feedbackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        AdaptiveWeightLearner.LearningSnapshot after = adaptiveWeightLearner.snapshot(MemoryScenario.CHAT);
        assertThat(after.pendingRecallSessions()).isZero();
        assertThat(after.shadowEvaluation().sampleCount()).isGreaterThanOrEqualTo(8);
        assertThat(after.active().getUpdateCount()).isGreaterThan(initialUpdates);
        assertThat(
                !after.active().getProfileName().equals(initialActiveProfile)
                        || Math.abs(after.active().getAlpha() - before.active().getAlpha()) > 1.0e-9
                        || Math.abs(after.active().getBeta() - before.active().getBeta()) > 1.0e-9
                        || Math.abs(after.active().getGamma() - before.active().getGamma()) > 1.0e-9)
                .isTrue();
    }

    private void storeFragment(MemoryFragment fragment) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/memory/store/fragment",
                fragment,
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("id", fragment.getId());
    }

    private MemoryFragment fragment(String id, String content, float[] embedding, int tokenCount) {
        return MemoryFragment.builder()
                .id(id + "-" + namespace)
                .namespace(namespace)
                .content(content)
                .embedding(embedding)
                .tokenCount(tokenCount)
                .importance(0.5)
                .tags(List.of("integration"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private void invalidateTaskCache(String taskId) throws Exception {
        Field field = SnapshotService.class.getDeclaredField("activeTasks");
        field.setAccessible(true);
        Cache<String, ?> cache = (Cache<String, ?>) field.get(snapshotService);
        cache.invalidate(taskId);
    }

    @SuppressWarnings("unchecked")
    private void clearAdaptiveRecallSessions() throws Exception {
        Field field = AdaptiveWeightLearner.class.getDeclaredField("recallSessions");
        field.setAccessible(true);
        Cache<String, ?> cache = (Cache<String, ?>) field.get(adaptiveWeightLearner);
        cache.invalidateAll();
    }

    @TestConfiguration
    static class TestEmbeddingConfig {

        @Bean("bgeSmallEmbeddingService")
        @Primary
        StubEmbeddingService stubEmbeddingService() {
            return new StubEmbeddingService();
        }

        static int tokenCount(String text) {
            if (text == null || text.isBlank()) {
                return 0;
            }
            return text.trim().split("\\s+").length;
        }

        static class StubEmbeddingService implements EmbeddingService, TokenCounter {

            @Override
            public float[] embed(String text) {
                float[] vector = semanticVector(text);
                normalize(vector);
                return vector;
            }

            @Override
            public int dimension() {
                return 4;
            }

            @Override
            public int countTokens(String text) {
                return tokenCount(text);
            }

            private float[] semanticVector(String text) {
                String normalized = text == null ? "" : text.toLowerCase();
                float[] vector = new float[4];
                vector[0] = score(normalized, Set.of("java", "thread", "safety", "lock", "locks", "volatile", "synchronization", "concurrency", "happens-before"));
                vector[1] = score(normalized, Set.of("python", "pandas", "dataframe", "joins", "notebooks", "visualization"));
                vector[2] = score(normalized, Set.of("task", "checkpoint", "recover", "dag", "node"));
                vector[3] = Math.max(1.0f, tokenCount(normalized));
                return vector;
            }

            private float score(String normalized, Set<String> keywords) {
                float value = 0.5f;
                for (String keyword : keywords) {
                    if (normalized.contains(keyword)) {
                        value += 1.0f;
                    }
                }
                return value;
            }

            private void normalize(float[] vector) {
                double norm = 0.0;
                for (float value : vector) {
                    norm += value * value;
                }
                norm = Math.sqrt(norm);
                if (norm == 0.0) {
                    return;
                }
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) (vector[i] / norm);
                }
            }
        }
    }
}
