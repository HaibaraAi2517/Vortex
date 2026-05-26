package com.vortex.app.integration;

import com.vortex.app.VortexApplication;
import com.vortex.app.integration.support.IsolatedIntegrationTestSupport;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.DagNode;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.snapshot.SnapshotService;
import com.vortex.storage.api.L2WarmStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {VortexApplication.class, CheckpointRetentionIT.TestEmbeddingConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true"
        })
@Import(IsolatedIntegrationTestSupport.Config.class)
@ContextConfiguration(initializers = IsolatedIntegrationTestSupport.Initializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CheckpointRetentionIT {

    private static final String TEST_NAMESPACE_PREFIX = "crit-";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("vortex.storage.l1.max-tokens", () -> 24);
        registry.add("vortex.storage.l3.minio.endpoint", () -> "http://localhost:9000");
        registry.add("vortex.storage.l3.minio.access-key", () -> "minioadmin");
        registry.add("vortex.storage.l3.minio.secret-key", () -> "minioadmin");
        registry.add("vortex.storage.l2.milvus.host", () -> "localhost");
        registry.add("vortex.storage.l2.milvus.port", () -> 19530);
        registry.add("vortex.storage.l2.embedding-dim", () -> 4);
        registry.add("vortex.kernel.embedding.bge.model-path", () -> "unused-in-it");
        registry.add("vortex.kernel.splitter.max-tokens-per-chunk", () -> 512);
        registry.add("vortex.kernel.namespace-quota.hard-fraction", () -> 1.0);
        registry.add("vortex.kernel.namespace-quota.soft-fraction", () -> 1.0);
        registry.add("vortex.kernel.namespace-quota.min-hard-tokens", () -> 24);
        registry.add("vortex.kernel.snapshot.checkpoint.max-deltas-before-full", () -> 2);
        registry.add("vortex.kernel.snapshot.checkpoint.rotation.max-per-task", () -> 2);
        registry.add("vortex.test.cleanup.external-stores", () -> false);
        registry.add("logging.level.com.vortex", () -> "INFO");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private InMemoryCheckpointStore minioColdStore;

    @Test
    void retentionKeepsRecoverableDeltaChainAfterFullRotation() {
        String namespace = TEST_NAMESPACE_PREFIX + UUID.randomUUID();

        String taskId = createTask(namespace);

        String firstNodeId = appendNode(taskId, "THOUGHT", "chain-one-full").getNodeId();
        String firstCheckpointId = checkpoint(taskId);

        String secondNodeId = appendNodeWithTarget(
                taskId, "ACTION", "chain-one-delta-a", firstNodeId, "CONTROL_DEP").getNodeId();
        String secondCheckpointId = checkpoint(taskId);

        appendNode(taskId, "THOUGHT", "chain-one-delta-b");
        String thirdCheckpointId = checkpoint(taskId);

        String fourthNodeId = appendNode(taskId, "THOUGHT", "chain-two-full").getNodeId();
        String fourthCheckpointId = checkpoint(taskId);

        String fifthNodeId = appendNodeWithTarget(
                taskId, "ACTION", "chain-two-delta-a", fourthNodeId, "CONTROL_DEP").getNodeId();
        String fifthCheckpointId = checkpoint(taskId);

        appendNode(taskId, "THOUGHT", "chain-two-delta-b");
        String sixthCheckpointId = checkpoint(taskId);

        ResponseEntity<CheckpointMetadata[]> checkpointsResponse = restTemplate.getForEntity(
                "/api/v1/tasks/" + taskId + "/checkpoints",
                CheckpointMetadata[].class);
        assertThat(checkpointsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        CheckpointMetadata[] checkpoints = checkpointsResponse.getBody();
        assertThat(checkpoints).isNotNull();
        assertThat(checkpoints)
                .extracting(CheckpointMetadata::getCheckpointId)
                .containsExactly(fourthCheckpointId, fifthCheckpointId, sixthCheckpointId);
        assertThat(checkpoints)
                .extracting(CheckpointMetadata::getType)
                .containsExactly(
                        CheckpointMetadata.CheckpointType.FULL,
                        CheckpointMetadata.CheckpointType.DELTA,
                        CheckpointMetadata.CheckpointType.DELTA);
        assertThat(checkpoints[1].getBaseCheckpointId()).isEqualTo(fourthCheckpointId);
        assertThat(checkpoints[2].getBaseCheckpointId()).isEqualTo(fifthCheckpointId);
        assertThat(checkpoints)
                .extracting(CheckpointMetadata::getCheckpointId)
                .doesNotContain(firstCheckpointId, secondCheckpointId, thirdCheckpointId);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshotService, "evictFromCacheForTest", taskId);

        ResponseEntity<TaskState> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", sixthCheckpointId),
                TaskState.class);
        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recoverResponse.getBody()).isNotNull();
        assertThat(recoverResponse.getBody().getLatestCheckpointId()).isEqualTo(sixthCheckpointId);

        TaskState recovered = snapshotService.getTask(taskId).orElseThrow();
        assertThat(recovered.getGraph().nodeCount()).isEqualTo(6);
        assertThat(recovered.getGraph().areConnected(firstNodeId, secondNodeId)).isTrue();
        assertThat(recovered.getGraph().areConnected(fourthNodeId, fifthNodeId)).isTrue();

        ResponseEntity<String> dagResponse = restTemplate.getForEntity(
                "/api/v1/tasks/" + taskId + "/dag",
                String.class);
        assertThat(dagResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dagResponse.getBody()).contains(
                "chain-one-full",
                "chain-one-delta-a",
                "chain-one-delta-b",
                "chain-two-full",
                "chain-two-delta-a",
                "chain-two-delta-b");
    }

    @Test
    void recoverReturnsConflictWhenDeltaChainIsBroken() {
        String namespace = TEST_NAMESPACE_PREFIX + UUID.randomUUID();
        String taskId = createTask(namespace);

        appendNode(taskId, "THOUGHT", "base-full");
        String fullCheckpointId = checkpoint(taskId);

        appendNode(taskId, "ACTION", "broken-delta");
        String deltaCheckpointId = checkpoint(taskId);

        minioColdStore.deleteCheckpoint(taskId + "/" + fullCheckpointId);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshotService, "evictFromCacheForTest", taskId);

        ResponseEntity<Map> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", deltaCheckpointId),
                Map.class);

        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(recoverResponse.getBody()).isNotNull();
        assertThat(recoverResponse.getBody()).containsEntry("error", "CHECKPOINT_RECOVERY_FAILED");
        assertThat(recoverResponse.getBody()).containsEntry("reason", "DELTA_CHAIN_BROKEN");
        assertThat(recoverResponse.getBody()).containsEntry("taskId", taskId);
    }

    @Test
    void recoverReturnsConflictWhenDeltaPayloadIsInvalid_andMetricsAreExposed() {
        String namespace = TEST_NAMESPACE_PREFIX + UUID.randomUUID();
        String taskId = createTask(namespace);

        appendNode(taskId, "THOUGHT", "base-full");
        checkpoint(taskId);

        appendNode(taskId, "ACTION", "corrupted-delta");
        String deltaCheckpointId = checkpoint(taskId);

        minioColdStore.putBytes("checkpoints/" + taskId + "/" + deltaCheckpointId + ".kryo",
                new byte[]{1, 2, 3, 4, 5});
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshotService, "evictFromCacheForTest", taskId);

        ResponseEntity<Map> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", deltaCheckpointId),
                Map.class);

        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(recoverResponse.getBody()).isNotNull();
        assertThat(recoverResponse.getBody()).containsEntry("error", "CHECKPOINT_RECOVERY_FAILED");
        assertThat(recoverResponse.getBody()).containsEntry("reason", "DELTA_PAYLOAD_INVALID");
        assertThat(recoverResponse.getBody()).containsEntry("taskId", taskId);

        ResponseEntity<Map> metricResponse = restTemplate.getForEntity(
                "/actuator/metrics/vortex.checkpoint.recovery.total?tag=outcome:failure&tag=reason:DELTA_PAYLOAD_INVALID",
                Map.class);
        assertThat(metricResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metricResponse.getBody()).isNotNull();
        assertThat(metricResponse.getBody()).containsEntry("name", "vortex.checkpoint.recovery.total");
        assertThat(metricResponse.getBody()).containsKey("measurements");
    }

    private String createTask(String namespace) {
        ResponseEntity<Map> createTask = restTemplate.postForEntity(
                "/api/v1/tasks",
                Map.of("description", "retention chain task", "namespace", namespace),
                Map.class);
        assertThat(createTask.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = (String) createTask.getBody().get("taskId");
        assertThat(taskId).isNotBlank();
        return taskId;
    }

    private DagNode appendNode(String taskId, String type, String content) {
        ResponseEntity<DagNode> response = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of("type", type, "content", content),
                DagNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private DagNode appendNodeWithTarget(
            String taskId, String type, String content, String targetNodeId, String edgeType) {
        ResponseEntity<DagNode> response = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/nodes",
                Map.of(
                        "type", type,
                        "content", content,
                        "targetNodeId", targetNodeId,
                        "edgeType", edgeType),
                DagNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private String checkpoint(String taskId) {
        ResponseEntity<Map> checkpointResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/checkpoint", null, Map.class);
        assertThat(checkpointResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String checkpointId = (String) checkpointResponse.getBody().get("checkpointId");
        assertThat(checkpointId).isNotBlank();
        return checkpointId;
    }

    @TestConfiguration
    static class TestEmbeddingConfig {

        @Bean("bgeSmallEmbeddingService")
        @Primary
        StubEmbeddingService stubEmbeddingService() {
            return new StubEmbeddingService();
        }

        @Bean(name = "milvusWarmStore")
        @Primary
        L2WarmStore inMemoryWarmStore() {
            return new InMemoryWarmStore();
        }

        @Bean(name = "minioColdStore")
        @Primary
        InMemoryCheckpointStore inMemoryCheckpointStore() {
            return new InMemoryCheckpointStore();
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
                if (text == null || text.isBlank()) {
                    return 0;
                }
                return text.trim().split("\\s+").length;
            }

            private float[] semanticVector(String text) {
                String normalized = text == null ? "" : text.toLowerCase();
                float[] vector = new float[4];
                vector[0] = score(normalized, Set.of("java", "thread", "safety", "lock", "locks"));
                vector[1] = score(normalized, Set.of("python", "pandas", "dataframe", "joins"));
                vector[2] = score(normalized, Set.of("task", "checkpoint", "recover", "dag", "node"));
                vector[3] = Math.max(1.0f, countTokens(text));
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

    static class InMemoryWarmStore implements L2WarmStore {
        private final java.util.concurrent.ConcurrentHashMap<String, MemoryFragment> fragments =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void upsert(MemoryFragment fragment) {
            fragments.put(fragment.getId(), fragment);
        }

        @Override
        public java.util.List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK) {
            return fragments.values().stream()
                    .filter(fragment -> namespace == null || namespace.equals(fragment.getNamespace()))
                    .limit(topK)
                    .toList();
        }

        @Override
        public java.util.Optional<MemoryFragment> get(String id) {
            return java.util.Optional.ofNullable(fragments.get(id));
        }

        @Override
        public void delete(String id) {
            fragments.remove(id);
        }

        @Override
        public int vectorDimension() {
            return 4;
        }
    }

    static class InMemoryCheckpointStore implements com.vortex.storage.api.L3ColdStore {
        private final java.util.concurrent.ConcurrentHashMap<String, byte[]> store =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, CheckpointMetadata> metadata =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final KryoSerializer serializer = new KryoSerializer();

        @Override
        public void archiveFragment(MemoryFragment fragment) {
            store.put("frag/" + fragment.getId(), serializer.serialize(fragment));
        }

        @Override
        public java.util.Optional<MemoryFragment> retrieveFragment(String id) {
            return java.util.Optional.ofNullable(store.get("frag/" + id))
                    .map(bytes -> serializer.deserialize(bytes, MemoryFragment.class));
        }

        @Override
        public String saveCheckpoint(TaskState state) {
            String checkpointId = state.getLatestCheckpointId() != null
                    ? state.getLatestCheckpointId()
                    : UUID.randomUUID().toString();
            state.setLatestCheckpointId(checkpointId);
            saveCheckpointWithMetadata(state, CheckpointMetadata.builder()
                    .checkpointId(checkpointId)
                    .taskId(state.getTaskId())
                    .sequenceNumber(state.getWalSequenceNumber())
                    .type(CheckpointMetadata.CheckpointType.FULL)
                    .build());
            return checkpointId;
        }

        @Override
        public CheckpointMetadata saveCheckpointWithMetadata(TaskState state, CheckpointMetadata meta) {
            store.put("cp/" + state.getTaskId() + "/" + meta.getCheckpointId(), serializer.serialize(state));
            if (meta.getCreatedAt() == null) {
                meta.setCreatedAt(state.getLastCheckpointAt());
            }
            meta.setL3Key("cp/" + state.getTaskId() + "/" + meta.getCheckpointId());
            metadata.put(state.getTaskId() + "/" + meta.getCheckpointId(), meta);
            return meta;
        }

        @Override
        public CheckpointMetadata saveCheckpointBytesWithMetadata(byte[] data, CheckpointMetadata meta) {
            String key = "checkpoints/" + meta.getTaskId() + "/" + meta.getCheckpointId() + ".kryo";
            store.put(key, data);
            if (meta.getCreatedAt() == null) {
                meta.setCreatedAt(java.time.Instant.now());
            }
            meta.setL3Key(key);
            metadata.put(meta.getTaskId() + "/" + meta.getCheckpointId(), meta);
            return meta;
        }

        @Override
        public java.util.Optional<TaskState> loadCheckpoint(String checkpointRef) {
            return java.util.Optional.ofNullable(store.get("cp/" + checkpointRef))
                    .map(bytes -> serializer.deserialize(bytes, TaskState.class));
        }

        @Override
        public void deleteCheckpoint(String checkpointRef) {
            store.remove("cp/" + checkpointRef);
            store.remove("checkpoints/" + checkpointRef + ".kryo");
            metadata.remove(checkpointRef);
        }

        @Override
        public void putBytes(String key, byte[] data) {
            store.put(key, data);
        }

        @Override
        public byte[] getBytes(String key) {
            return store.get(key);
        }

        @Override
        public java.util.List<CheckpointMetadata> listCheckpointMetadata(String taskId) {
            return metadata.values().stream()
                    .filter(meta -> taskId.equals(meta.getTaskId()))
                    .sorted(java.util.Comparator.comparing(CheckpointMetadata::getCreatedAt))
                    .toList();
        }

        @Override
        public java.util.Set<String> listTaskIdsWithCheckpoints() {
            return metadata.values().stream()
                    .map(CheckpointMetadata::getTaskId)
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
