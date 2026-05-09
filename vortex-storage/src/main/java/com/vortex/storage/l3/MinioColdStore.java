package com.vortex.storage.l3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.storage.api.L3ColdStore;
import io.minio.*;
import io.minio.errors.MinioException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * L3 Cold Store backed by MinIO (S3-compatible).
 *
 * Object key layout:
 *   fragments/{id}.json
 *   checkpoints/{taskId}/{checkpointId}.json
 */
@Slf4j
@Component
public class MinioColdStore implements L3ColdStore {

    private static final String PREFIX_FRAGMENT = "fragments/";
    private static final String PREFIX_CHECKPOINT = "checkpoints/";

    private final MinioClient minioClient;
    private final String bucket;
    private final ObjectMapper objectMapper;

    public MinioColdStore(
            @Value("${vortex.storage.l3.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${vortex.storage.l3.minio.access-key:minioadmin}") String accessKey,
            @Value("${vortex.storage.l3.minio.secret-key:minioadmin}") String secretKey,
            @Value("${vortex.storage.l3.minio.bucket:vortex}") String bucket) {
        this.bucket = bucket;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket '{}' created", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to initialise MinIO bucket '{}': {}", bucket, e.getMessage());
        }
    }

    @Override
    public void archiveFragment(MemoryFragment fragment) {
        String key = PREFIX_FRAGMENT + fragment.getId() + ".json";
        putJson(key, fragment);
    }

    @Override
    public Optional<MemoryFragment> retrieveFragment(String id) {
        String key = PREFIX_FRAGMENT + id + ".json";
        return getJson(key, MemoryFragment.class);
    }

    @Override
    public String saveCheckpoint(TaskState state) {
        String checkpointId = UUID.randomUUID().toString();
        state.setLatestCheckpointId(checkpointId);
        state.setLastCheckpointAt(Instant.now());
        String key = PREFIX_CHECKPOINT + state.getTaskId() + "/" + checkpointId + ".json";
        putJson(key, state);
        log.info("Checkpoint saved taskId={} checkpointId={}", state.getTaskId(), checkpointId);
        return checkpointId;
    }

    @Override
    public Optional<TaskState> loadCheckpoint(String checkpointId) {
        // checkpointId format: taskId/uuid — stored as checkpoints/{taskId}/{uuid}.json
        // For MVP we accept the full key suffix
        String key = PREFIX_CHECKPOINT + checkpointId + ".json";
        return getJson(key, TaskState.class);
    }

    @Override
    public void deleteCheckpoint(String checkpointId) {
        String key = PREFIX_CHECKPOINT + checkpointId + ".json";
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            log.warn("Failed to delete checkpoint key={}: {}", key, e.getMessage());
        }
    }

    // ---- helpers ----

    private void putJson(String key, Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/json")
                    .build());
        } catch (Exception e) {
            log.error("MinIO put failed key={}: {}", key, e.getMessage());
            throw new IllegalStateException("MinIO put failed for key " + key, e);
        }
    }

    private <T> Optional<T> getJson(String key, Class<T> type) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return Optional.of(objectMapper.readValue(is, type));
        } catch (MinioException e) {
            log.debug("MinIO object not found key={}", key);
            return Optional.empty();
        } catch (Exception e) {
            log.error("MinIO get failed key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }
}
