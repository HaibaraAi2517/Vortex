package com.vortex.app.integration;

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
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test that connects to pre-started docker-compose services.
 * Run: docker compose up -d && mvn verify -pl vortex-app -am -Pintegration
 */
@SpringBootTest(
        classes = {VortexApplication.class, DockerComposeIT.TestEmbeddingConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@TestPropertySource(properties = {
        "vortex.storage.l1.max-tokens=24",
        "vortex.storage.l3.minio.endpoint=http://localhost:9000",
        "vortex.storage.l3.minio.access-key=minioadmin",
        "vortex.storage.l3.minio.secret-key=minioadmin",
        "vortex.storage.l3.minio.bucket=vortex-it",
        "vortex.storage.l2.milvus.host=localhost",
        "vortex.storage.l2.milvus.port=19530",
        "vortex.storage.l2.embedding-dim=4",
        "vortex.storage.l2.milvus.drop-collection-on-startup=true",
        "vortex.storage.l2.milvus.drop-collection-confirm-token=I-KNOW-WHAT-I-AM-DOING",
        "vortex.kernel.embedding.bge.model-path=unused-in-it",
        "vortex.kernel.splitter.max-tokens-per-chunk=512",
        "vortex.kernel.namespace-quota.hard-fraction=1.0",
        "vortex.kernel.namespace-quota.soft-fraction=1.0",
        "vortex.kernel.namespace-quota.min-hard-tokens=24",
        "vortex.kernel.learning.min-samples-before-promotion=1",
        "vortex.kernel.learning.shadow-promotion-window-days=0",
        "logging.level.com.vortex=INFO"
})
public class DockerComposeIT {

    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

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

    @BeforeEach
    void setUp() {
        namespace = "dkit-" + UUID.randomUUID();
        l1HotStore.clear(namespace);
        adaptiveWeightLearner.clearPendingSessionsForTest();
    }

    @AfterEach
    void tearDown() {
        l1HotStore.clear(namespace);
        adaptiveWeightLearner.clearPendingSessionsForTest();
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
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) health.getBody().get("l1TokensUsed")).longValue()).isLessThanOrEqualTo(24L);
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

        snapshotService.evictFromCacheForTest(taskId);

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

    private void storeFragment(MemoryFragment fragment) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/memory/store/fragment", fragment, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("id", fragment.getId());
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

