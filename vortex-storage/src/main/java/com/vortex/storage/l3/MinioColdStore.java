package com.vortex.storage.l3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.common.serialization.JacksonCompatibilityBridge;
import com.vortex.common.serialization.JsonMapperFactory;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.storage.api.CheckpointStoreException;
import com.vortex.storage.api.L3ColdStore;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * L3 Cold Store backed by MinIO (S3-compatible).
 *
 * Object key layout:
 *   fragments/{id}.json
 *   checkpoints/{taskId}/{checkpointId}.kryo   (Kryo binary, default)
 *   checkpoints/{taskId}/{checkpointId}.json   (Jackson JSON, legacy)
 */
@Slf4j
@Component
public class MinioColdStore implements L3ColdStore {

    private static final String PREFIX_FRAGMENT = "fragments/";
    private static final String PREFIX_CHECKPOINT = "checkpoints/";
    private static final String METADATA_SUFFIX = ".meta.json";
    private static final String KRYO_SUFFIX = ".kryo";
    private static final String JSON_SUFFIX = ".json";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_BINARY = "application/octet-stream";

    private final MinioClient minioClient;
    private final String bucket;
    private final String keyPrefix;
    private final ObjectMapper objectMapper;
    private final KryoSerializer kryoSerializer;

    @Autowired
    public MinioColdStore(
            @Value("${vortex.storage.l3.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${vortex.storage.l3.minio.access-key:minioadmin}") String accessKey,
            @Value("${vortex.storage.l3.minio.secret-key:minioadmin}") String secretKey,
            @Value("${vortex.storage.l3.minio.bucket:vortex}") String bucket,
            @Value("${vortex.storage.l3.minio.key-prefix:}") String keyPrefix) {
        this(
                MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build(),
                bucket,
                keyPrefix
        );
    }

    MinioColdStore(MinioClient minioClient, String bucket, String keyPrefix) {
        this.bucket = bucket;
        this.keyPrefix = normalizeKeyPrefix(keyPrefix);
        this.objectMapper = JsonMapperFactory.create();
        this.kryoSerializer = new KryoSerializer();
        this.minioClient = minioClient;
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
            throw new IllegalStateException("Failed to initialise MinIO bucket '" + bucket + "'", e);
        }
    }

    // ---- Fragment archival (unchanged) ----

    @Override
    public void archiveFragment(MemoryFragment fragment) {
        putJson(fragmentKey(fragment.getId()), fragment);
    }

    @Override
    public Optional<MemoryFragment> retrieveFragment(String id) {
        return getJson(fragmentKey(id), MemoryFragment.class);
    }

    @Override
    public void deleteFragment(String id) {
        String key = fragmentKey(id);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(applyKeyPrefix(key))
                    .build());
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) {
                log.debug("No fragment to delete for {}", id);
                return;
            }
            log.error("MinIO delete failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.DELETE_FAILED,
                    "MinIO delete failed for key " + key,
                    e);
        } catch (io.minio.errors.MinioException e) {
            log.error("MinIO delete failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.DELETE_FAILED,
                    "MinIO delete failed for key " + key,
                    e);
        } catch (Exception e) {
            log.error("MinIO delete failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.DELETE_FAILED,
                    "MinIO delete failed for key " + key,
                    e);
        }
    }

    // ---- Checkpoint (Kryo binary with Jackson fallback) ----

    @Override
    public String saveCheckpoint(TaskState state) {
        String checkpointId = UUID.randomUUID().toString();
        state.setLatestCheckpointId(checkpointId);
        state.setLastCheckpointAt(Instant.now());
        String key = checkpointDataKey(state.getTaskId(), checkpointId);

        byte[] compressed = kryoSerializer.serializeCompressed(state);
        putBinary(key, compressed);
        putJson(checkpointMetadataKey(state.getTaskId(), checkpointId), buildMetadata(state, checkpointId, key, compressed.length));

        log.info("Checkpoint saved taskId={} checkpointId={} sizeBytes={}",
                state.getTaskId(), checkpointId, compressed.length);
        return checkpointId;
    }

    /**
     * Save checkpoint with explicit metadata, returning CheckpointMetadata.
     */
    @Override
    public CheckpointMetadata saveCheckpointWithMetadata(TaskState state, CheckpointMetadata meta) {
        String checkpointId = meta.getCheckpointId();
        state.setLatestCheckpointId(checkpointId);
        state.setLastCheckpointAt(Instant.now());
        String key = checkpointDataKey(state.getTaskId(), checkpointId);

        byte[] compressed = kryoSerializer.serializeCompressed(state);
        putBinary(key, compressed);

        meta.setSizeBytes(compressed.length);
        meta.setL3Key(key);
        meta.setCompressed(true);
        meta.setCompressionAlgorithm("gzip");
        meta.setCreatedAt(Instant.now());
        putJson(checkpointMetadataKey(state.getTaskId(), checkpointId), meta);

        log.info("Checkpoint saved taskId={} checkpointId={} type={} sizeBytes={}",
                state.getTaskId(), checkpointId, meta.getType(), compressed.length);
        return meta;
    }

    @Override
    public CheckpointMetadata saveCheckpointBytesWithMetadata(byte[] data, CheckpointMetadata meta) {
        String checkpointId = meta.getCheckpointId();
        String key = checkpointDataKey(meta.getTaskId(), checkpointId);

        putBinary(key, data);

        meta.setSizeBytes(data.length);
        meta.setL3Key(key);
        meta.setCompressed(true);
        meta.setCompressionAlgorithm("gzip");
        meta.setCreatedAt(Instant.now());
        putJson(checkpointMetadataKey(meta.getTaskId(), checkpointId), meta);

        log.info("Checkpoint bytes saved taskId={} checkpointId={} type={} sizeBytes={}",
                meta.getTaskId(), checkpointId, meta.getType(), data.length);
        return meta;
    }

    @Override
    public Optional<TaskState> loadCheckpoint(String checkpointRef) {
        String checkpointBaseKey = normalizeCheckpointBaseKey(checkpointRef);

        // Try Kryo format first
        String kryoKey = checkpointBaseKey + KRYO_SUFFIX;
        byte[] data = getBinary(kryoKey);
        if (data != null && data.length > 0) {
            if (KryoSerializer.isKryoFormat(data)) {
                try {
                    TaskState state = kryoSerializer.deserializeCompressed(data, TaskState.class);
                    log.debug("Checkpoint loaded via Kryo: {}", checkpointRef);
                    return Optional.of(state);
                } catch (KryoSerializer.VersionMismatchException e) {
                    throw new CheckpointStoreException(
                            CheckpointStoreException.FailureType.VERSION_MISMATCH,
                            "Checkpoint payload version mismatch for ref " + checkpointRef
                                    + " expected=" + e.getExpectedVersion()
                                    + " actual=" + e.getActualVersion(),
                            e);
                } catch (Exception e) {
                    log.warn("Kryo deserialization failed for {}, trying decompress fallback", checkpointRef, e);
                    // Try uncompressed Kryo
                    try {
                        return Optional.of(kryoSerializer.deserialize(data, TaskState.class));
                    } catch (KryoSerializer.VersionMismatchException e2) {
                        e2.addSuppressed(e);
                        throw new CheckpointStoreException(
                                CheckpointStoreException.FailureType.VERSION_MISMATCH,
                                "Checkpoint payload version mismatch for ref " + checkpointRef
                                        + " expected=" + e2.getExpectedVersion()
                                        + " actual=" + e2.getActualVersion(),
                                e2);
                    } catch (Exception e2) {
                        e2.addSuppressed(e);
                        throw new CheckpointStoreException(
                                CheckpointStoreException.FailureType.PAYLOAD_INVALID,
                                "Checkpoint payload invalid for ref " + checkpointRef,
                                e2);
                    }
                }
            } else if (JacksonCompatibilityBridge.isJacksonFormat(data)) {
                log.info("Detected legacy Jackson format for checkpoint {}, migrating...", checkpointRef);
                try {
                    return Optional.of(JacksonCompatibilityBridge.migrateFromJackson(data));
                } catch (Exception e) {
                    throw new CheckpointStoreException(
                            CheckpointStoreException.FailureType.PAYLOAD_INVALID,
                            "Checkpoint payload invalid for ref " + checkpointRef,
                            e);
                }
            } else {
                throw new CheckpointStoreException(
                        CheckpointStoreException.FailureType.PAYLOAD_INVALID,
                        "Checkpoint payload invalid for ref " + checkpointRef + ": unsupported format in " + kryoKey,
                        null);
            }
        }

        // Try legacy Jackson JSON format
        String jsonKey = checkpointBaseKey + JSON_SUFFIX;
        byte[] jsonData = getBinary(jsonKey);
        if (jsonData != null && jsonData.length > 0) {
            try {
                return Optional.of(JacksonCompatibilityBridge.migrateFromJackson(jsonData));
            } catch (Exception e) {
                throw new CheckpointStoreException(
                        CheckpointStoreException.FailureType.PAYLOAD_INVALID,
                        "Checkpoint payload invalid for ref " + checkpointRef,
                        e);
            }
        }

        log.debug("Checkpoint not found in L3: {}", checkpointRef);
        return Optional.empty();
    }

    @Override
    public void deleteCheckpoint(String checkpointRef) {
        String checkpointBaseKey = normalizeCheckpointBaseKey(checkpointRef);

        // Try both formats
        String kryoKey = checkpointBaseKey + KRYO_SUFFIX;
        String jsonKey = checkpointBaseKey + JSON_SUFFIX;
        String metaKey = checkpointBaseKey + METADATA_SUFFIX;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(applyKeyPrefix(kryoKey)).build());
        } catch (Exception e) {
            log.debug("No .kryo checkpoint to delete for {}", checkpointRef);
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(applyKeyPrefix(jsonKey)).build());
        } catch (Exception e) {
            log.debug("No .json checkpoint to delete for {}", checkpointRef);
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(applyKeyPrefix(metaKey)).build());
        } catch (Exception e) {
            log.debug("No metadata checkpoint to delete for {}", checkpointRef);
        }
    }

    @Override
    public void putBytes(String key, byte[] data) {
        putBinary(key, data);
    }

    @Override
    public byte[] getBytes(String key) {
        return getBinary(key);
    }

    @Override
    public List<CheckpointMetadata> listCheckpointMetadata(String taskId) {
        String prefix = PREFIX_CHECKPOINT + taskId + "/";
        List<String> keys = listObjects(prefix);
        Map<String, CheckpointMetadata> metadataById = new HashMap<>();

        for (String key : keys) {
            if (key.endsWith(METADATA_SUFFIX)) {
                extractCheckpointId(taskId, key, METADATA_SUFFIX)
                        .flatMap(id -> getJson(key, CheckpointMetadata.class)
                                .map(meta -> {
                                    if (meta.getCheckpointId() == null) {
                                        meta.setCheckpointId(id);
                                    }
                                    if (meta.getTaskId() == null) {
                                        meta.setTaskId(taskId);
                                    }
                                    if (meta.getL3Key() == null) {
                                        meta.setL3Key(checkpointDataKey(taskId, id));
                                    }
                                    return meta;
                                }))
                        .ifPresent(meta -> metadataById.put(meta.getCheckpointId(), meta));
            }
        }

        for (String key : keys) {
            if (key.endsWith(METADATA_SUFFIX)) {
                continue;
            }
            if (!key.endsWith(KRYO_SUFFIX) && !key.endsWith(JSON_SUFFIX)) {
                continue;
            }
            String suffix = key.endsWith(KRYO_SUFFIX) ? KRYO_SUFFIX : JSON_SUFFIX;
            extractCheckpointId(taskId, key, suffix).ifPresent(id ->
                    metadataById.computeIfAbsent(id, cpId -> buildFallbackMetadata(taskId, cpId, key)));
        }

        List<CheckpointMetadata> metadata = new ArrayList<>(metadataById.values());
        metadata.sort(CheckpointMetadata.chronologicalOrder());
        return metadata;
    }

    @Override
    public Set<String> listTaskIdsWithCheckpoints() {
        List<String> keys = listObjects(PREFIX_CHECKPOINT);
        Set<String> taskIds = new LinkedHashSet<>();
        for (String key : keys) {
            String remainder = key.substring(PREFIX_CHECKPOINT.length());
            int separator = remainder.indexOf('/');
            if (separator > 0) {
                taskIds.add(remainder.substring(0, separator));
            }
        }
        return taskIds;
    }

    // ---- Object listing ----

    /**
     * List all object keys under a given prefix.
     */
    public List<String> listObjects(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(applyKeyPrefix(prefix))
                            .recursive(true)
                            .build());
            for (Result<Item> result : results) {
                keys.add(stripKeyPrefix(result.get().objectName()));
            }
        } catch (Exception e) {
            log.error("Failed to list objects with prefix '{}': {}", prefix, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.METADATA_READ_FAILED,
                    "MinIO list failed for prefix " + prefix,
                    e);
        }
        return keys;
    }

    /**
     * List all checkpoint keys for a given task.
     */
    public List<String> listCheckpointsForTask(String taskId) {
        String prefix = PREFIX_CHECKPOINT + taskId + "/";
        return listObjects(prefix);
    }

    /**
     * Delete all objects under a given prefix.
     */
    public void deleteObjectsByPrefix(String prefix) {
        List<String> keys = listObjects(prefix);
        for (String key : keys) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(applyKeyPrefix(key))
                        .build());
            } catch (Exception e) {
                log.warn("Failed to delete object {}: {}", key, e.getMessage());
            }
        }
        log.info("Deleted {} objects with prefix '{}'", keys.size(), prefix);
    }

    /**
     * Get the byte size of an object, or 0 if not found.
     */
    public long getObjectSize(String key) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(applyKeyPrefix(key)).build());
            return stat.size();
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) {
                log.debug("MinIO object not found during stat key={}", key);
                return 0;
            }
            log.error("MinIO stat failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.METADATA_READ_FAILED,
                    "MinIO stat failed for key " + key,
                    e);
        } catch (io.minio.errors.MinioException e) {
            log.error("MinIO stat failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.METADATA_READ_FAILED,
                    "MinIO stat failed for key " + key,
                    e);
        } catch (Exception e) {
            log.error("MinIO stat failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.METADATA_READ_FAILED,
                    "MinIO stat failed for key " + key,
                    e);
        }
    }

    // ---- Helpers ----

    private void putJson(String key, Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(applyKeyPrefix(key))
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(CONTENT_TYPE_JSON)
                    .build());
        } catch (Exception e) {
            log.error("MinIO put failed key={}: {}", key, e.getMessage());
            throw new IllegalStateException("MinIO put failed for key " + key, e);
        }
    }

    private void putBinary(String key, byte[] data) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(applyKeyPrefix(key))
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(CONTENT_TYPE_BINARY)
                    .build());
        } catch (Exception e) {
            log.error("MinIO binary put failed key={}: {}", key, e.getMessage());
            throw new IllegalStateException("MinIO put failed for key " + key, e);
        }
    }

    private CheckpointMetadata buildMetadata(TaskState state, String checkpointId, String l3Key, long sizeBytes) {
        return CheckpointMetadata.builder()
                .checkpointId(checkpointId)
                .taskId(state.getTaskId())
                .sequenceNumber(state.getWalSequenceNumber())
                .type(CheckpointMetadata.CheckpointType.FULL)
                .nodeCount(state.getGraph().nodeCount())
                .edgeCount(state.getGraph().edgeCount())
                .sizeBytes(sizeBytes)
                .compressed(true)
                .compressionAlgorithm("gzip")
                .branchId(state.getCurrentBranchId())
                .createdAt(Optional.ofNullable(state.getLastCheckpointAt()).orElse(Instant.now()))
                .l3Key(l3Key)
                .build();
    }

    private CheckpointMetadata buildFallbackMetadata(String taskId, String checkpointId, String key) {
        StatObjectResponse stat = statObject(key);
        return CheckpointMetadata.builder()
                .checkpointId(checkpointId)
                .taskId(taskId)
                .type(CheckpointMetadata.CheckpointType.FULL)
                .sizeBytes(stat.size())
                .createdAt(Optional.ofNullable(stat.lastModified()).map(t -> t.toInstant()).orElse(Instant.EPOCH))
                .l3Key(key)
                .build();
    }

    private String checkpointDataKey(String taskId, String checkpointId) {
        return checkpointBaseKey(taskId, checkpointId) + KRYO_SUFFIX;
    }

    private String checkpointMetadataKey(String taskId, String checkpointId) {
        return checkpointBaseKey(taskId, checkpointId) + METADATA_SUFFIX;
    }

    private Optional<String> extractCheckpointId(String taskId, String key, String suffix) {
        String prefix = checkpointPrefix(taskId);
        if (!key.startsWith(prefix) || !key.endsWith(suffix)) {
            return Optional.empty();
        }
        return Optional.of(key.substring(prefix.length(), key.length() - suffix.length()));
    }

    private StatObjectResponse statObject(String key) {
        try {
            return minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(applyKeyPrefix(key)).build());
        } catch (Exception e) {
            log.error("MinIO stat failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.METADATA_READ_FAILED,
                    "MinIO stat failed for key " + key,
                    e);
        }
    }

    private <T> Optional<T> getJson(String key, Class<T> type) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(applyKeyPrefix(key)).build())) {
            return Optional.of(objectMapper.readValue(is, type));
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) {
                log.debug("MinIO object not found key={}", key);
                return Optional.empty();
            }
            log.error("MinIO get failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.READ_FAILED,
                    "MinIO get failed for key " + key,
                    e);
        } catch (io.minio.errors.MinioException e) {
            log.error("MinIO get failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.READ_FAILED,
                    "MinIO get failed for key " + key,
                    e);
        } catch (Exception e) {
            log.error("MinIO get failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.READ_FAILED,
                    "MinIO get failed for key " + key,
                    e);
        }
    }

    private byte[] getBinary(String key) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(applyKeyPrefix(key)).build())) {
            return is.readAllBytes();
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) {
                log.debug("MinIO binary object not found key={}", key);
                return null;
            }
            log.error("MinIO binary get failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.READ_FAILED,
                    "MinIO binary get failed for key " + key,
                    e);
        } catch (io.minio.errors.MinioException e) {
            log.error("MinIO binary get failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.READ_FAILED,
                    "MinIO binary get failed for key " + key,
                    e);
        } catch (Exception e) {
            log.error("MinIO binary get failed key={}: {}", key, e.getMessage());
            throw new CheckpointStoreException(
                    CheckpointStoreException.FailureType.READ_FAILED,
                    "MinIO binary get failed for key " + key,
                    e);
        }
    }

    private boolean isNotFound(ErrorResponseException e) {
        return e.errorResponse() != null
                && ("NoSuchKey".equalsIgnoreCase(e.errorResponse().code())
                || "NoSuchObject".equalsIgnoreCase(e.errorResponse().code()));
    }

    private String fragmentKey(String fragmentId) {
        return PREFIX_FRAGMENT + fragmentId + JSON_SUFFIX;
    }

    private String checkpointPrefix(String taskId) {
        return PREFIX_CHECKPOINT + taskId + "/";
    }

    private String checkpointBaseKey(String taskId, String checkpointId) {
        return checkpointPrefix(taskId) + checkpointId;
    }

    private String normalizeCheckpointBaseKey(String checkpointRef) {
        String logicalRef = stripKeyPrefix(checkpointRef == null ? "" : checkpointRef.trim());
        if (logicalRef.endsWith(METADATA_SUFFIX)) {
            logicalRef = logicalRef.substring(0, logicalRef.length() - METADATA_SUFFIX.length());
        } else if (logicalRef.endsWith(KRYO_SUFFIX)) {
            logicalRef = logicalRef.substring(0, logicalRef.length() - KRYO_SUFFIX.length());
        } else if (logicalRef.endsWith(JSON_SUFFIX)) {
            logicalRef = logicalRef.substring(0, logicalRef.length() - JSON_SUFFIX.length());
        }
        if (logicalRef.startsWith(PREFIX_CHECKPOINT)) {
            return logicalRef;
        }
        return PREFIX_CHECKPOINT + logicalRef;
    }

    private String applyKeyPrefix(String key) {
        return keyPrefix.isEmpty() ? key : keyPrefix + key;
    }

    private String stripKeyPrefix(String key) {
        if (key == null || keyPrefix.isEmpty() || !key.startsWith(keyPrefix)) {
            return key;
        }
        return key.substring(keyPrefix.length());
    }

    private static String normalizeKeyPrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String normalized = prefix.trim().replace('\\', '/');
        if (normalized.isEmpty()) {
            return "";
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
