package com.vortex.app.integration;

import com.vortex.app.VortexApplication;
import com.vortex.app.integration.support.IsolatedIntegrationTestSupport;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.kernel.snapshot.SnapshotService;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
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
        classes = {VortexApplication.class, CheckpointRecoveryFailureIT.TestEmbeddingConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@Import(IsolatedIntegrationTestSupport.Config.class)
@ContextConfiguration(initializers = IsolatedIntegrationTestSupport.Initializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CheckpointRecoveryFailureIT {

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
        registry.add("vortex.test.cleanup.external-stores", () -> false);
        registry.add("logging.level.com.vortex", () -> "INFO");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private FailingRecoveryStore failingRecoveryStore;

    @Autowired
    private SnapshotService snapshotService;

    @Test
    void recoverWithNonExistentCheckpointId_returns404() {
        String taskId = createTask("recover-failure-" + UUID.randomUUID());
        checkpoint(taskId);

        String fakeCheckpointId = UUID.randomUUID().toString();
        ResponseEntity<Map> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                Map.of("checkpointId", fakeCheckpointId),
                Map.class);

        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(recoverResponse.getBody()).isNotNull();
        assertThat(recoverResponse.getBody()).containsEntry("error", "CHECKPOINT_RECOVERY_FAILED");
        assertThat(recoverResponse.getBody()).containsEntry("reason", "CHECKPOINT_METADATA_MISSING");
        assertThat(recoverResponse.getBody()).containsEntry("taskId", taskId);
        assertThat(recoverResponse.getBody()).containsEntry("checkpointId", fakeCheckpointId);
    }

    @Test
    void recoverWhenCheckpointMetadataLoadFails_returns500() {
        String taskId = createTask("recover-storage-failure-" + UUID.randomUUID());
        checkpoint(taskId);
        failingRecoveryStore.failMetadataReadsForTask(taskId);

        ResponseEntity<Map> recoverResponse = restTemplate.postForEntity(
                "/api/v1/tasks/" + taskId + "/recover",
                null,
                Map.class);

        assertThat(recoverResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(recoverResponse.getBody()).isNotNull();
        assertThat(recoverResponse.getBody()).containsEntry("error", "CHECKPOINT_RECOVERY_FAILED");
        assertThat(recoverResponse.getBody()).containsEntry("reason", "CHECKPOINT_METADATA_LOAD_FAILED");
        assertThat(recoverResponse.getBody()).containsEntry("taskId", taskId);
    }

    @Test
    void getTaskWhenLazyRecoveryFails_returns500() {
        String taskId = createTask("lazy-recover-failure-" + UUID.randomUUID());
        checkpoint(taskId);
        snapshotService.evictFromCacheForTest(taskId);
        failingRecoveryStore.failMetadataReadsForTask(taskId);

        ResponseEntity<Map> getTaskResponse = restTemplate.getForEntity(
                "/api/v1/tasks/" + taskId,
                Map.class);

        assertThat(getTaskResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(getTaskResponse.getBody()).isNotNull();
        assertThat(getTaskResponse.getBody()).containsEntry("error", "CHECKPOINT_RECOVERY_FAILED");
        assertThat(getTaskResponse.getBody()).containsEntry("reason", "CHECKPOINT_METADATA_LOAD_FAILED");
        assertThat(getTaskResponse.getBody()).containsEntry("taskId", taskId);
    }

    private String createTask(String namespace) {
        ResponseEntity<Map> createTask = restTemplate.postForEntity(
                "/api/v1/tasks",
                Map.of("description", "checkpoint recovery failure task", "namespace", namespace),
                Map.class);
        assertThat(createTask.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = (String) createTask.getBody().get("taskId");
        assertThat(taskId).isNotBlank();
        return taskId;
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
        FailingRecoveryStore failingRecoveryStore() {
            return new FailingRecoveryStore();
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

    static class FailingRecoveryStore implements L3ColdStore {
        private final java.util.concurrent.ConcurrentHashMap<String, byte[]> store =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, CheckpointMetadata> metadata =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final KryoSerializer serializer = new KryoSerializer();
        private volatile String failMetadataTaskId;

        void failMetadataReadsForTask(String taskId) {
            this.failMetadataTaskId = taskId;
        }

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
            if (taskId.equals(failMetadataTaskId)) {
                throw new IllegalStateException("simulated metadata read failure");
            }
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
