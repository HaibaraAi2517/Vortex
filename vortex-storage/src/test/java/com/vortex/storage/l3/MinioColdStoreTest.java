package com.vortex.storage.l3;

import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.storage.api.CheckpointStoreException;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;
import okhttp3.Headers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioColdStoreTest {

    @Test
    void archiveFragmentAppliesConfiguredKeyPrefix() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123");

        MemoryFragment fragment = MemoryFragment.builder()
                .id("frag-1")
                .namespace("ns")
                .content("content")
                .tokenCount(1)
                .build();

        coldStore.archiveFragment(fragment);

        verify(minioClient).putObject(argThatPutObject("vortex-it", "run-123/fragments/frag-1.json"));
    }

    @Test
    void init_failsFastWhenBucketInitializationFails() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new IOException("simulated bucket init failure"));

        assertThatThrownBy(coldStore::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to initialise MinIO bucket 'vortex-it'")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void deleteFragment_ignoresMissingObjectResponses() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        ErrorResponseException notFound = mock(ErrorResponseException.class);
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(notFound.errorResponse()).thenReturn(errorResponse);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        doThrow(notFound).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        coldStore.deleteFragment("frag-1");

        verify(minioClient).removeObject(argThatRemoveObject("vortex-it", "run-123/fragments/frag-1.json"));
    }

    @Test
    void deleteFragment_propagatesMinioServerFailureInsteadOfPretendingSuccess() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        doThrow(new ServerException("simulated delete failure", 500, "req-789"))
                .when(minioClient)
                .removeObject(any(RemoveObjectArgs.class));

        assertThatThrownBy(() -> coldStore.deleteFragment("frag-1"))
                .isInstanceOf(CheckpointStoreException.class)
                .satisfies(ex -> assertThat(((CheckpointStoreException) ex).getFailureType())
                        .isEqualTo(CheckpointStoreException.FailureType.DELETE_FAILED))
                .hasMessageContaining("MinIO delete failed");
    }

    @Test
    void loadCheckpointAcceptsTaskScopedReference() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        TaskState state = TaskState.builder()
                .taskId("task-1")
                .description("checkpoint")
                .build();
        byte[] payload = serialize(coldStore, state);

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(getObjectResponse(payload));

        Optional<TaskState> loaded = coldStore.loadCheckpoint("task-1/" + state.getLatestCheckpointId());

        assertThat(loaded).isPresent();
        verify(minioClient).getObject(argThatGetObject("vortex-it",
                "run-123/checkpoints/task-1/" + state.getLatestCheckpointId() + ".kryo"));
    }

    @Test
    void loadCheckpointAcceptsFullCheckpointKey() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        TaskState state = TaskState.builder()
                .taskId("task-1")
                .description("checkpoint")
                .build();
        byte[] payload = serialize(coldStore, state);
        String checkpointId = state.getLatestCheckpointId();

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(getObjectResponse(payload));

        Optional<TaskState> loaded = coldStore.loadCheckpoint("checkpoints/task-1/" + checkpointId + ".kryo");

        assertThat(loaded).isPresent();
        verify(minioClient).getObject(argThatGetObject("vortex-it",
                "run-123/checkpoints/task-1/" + checkpointId + ".kryo"));
    }

    @Test
    void listObjectsStripsConfiguredKeyPrefix() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        @SuppressWarnings("unchecked")
        Result<Item> result = mock(Result.class);
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn("run-123/checkpoints/task-1/cp-1.kryo");
        when(result.get()).thenReturn(item);
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(result));

        List<String> keys = coldStore.listObjects("checkpoints/task-1/");

        assertThat(keys).containsExactly("checkpoints/task-1/cp-1.kryo");
        verify(minioClient).listObjects(argThatListObjects("vortex-it", "run-123/checkpoints/task-1/"));
    }

    @Test
    void deleteCheckpointRemovesPrefixedDataAndMetadataObjects() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        coldStore.deleteCheckpoint("task-1/cp-1");

        verify(minioClient).removeObject(argThatRemoveObject("vortex-it", "run-123/checkpoints/task-1/cp-1.kryo"));
        verify(minioClient).removeObject(argThatRemoveObject("vortex-it", "run-123/checkpoints/task-1/cp-1.json"));
        verify(minioClient).removeObject(argThatRemoveObject("vortex-it", "run-123/checkpoints/task-1/cp-1.meta.json"));
    }

    @Test
    void listCheckpointMetadataBuildsLogicalKeysWithoutPrefix() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        @SuppressWarnings("unchecked")
        Result<Item> dataResult = mock(Result.class);
        Item dataItem = mock(Item.class);
        when(dataItem.objectName()).thenReturn("run-123/checkpoints/task-1/cp-1.kryo");
        when(dataResult.get()).thenReturn(dataItem);
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(dataResult));

        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(128L);
        when(stat.lastModified()).thenReturn(ZonedDateTime.now());
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        assertThat(coldStore.listCheckpointMetadata("task-1"))
                .singleElement()
                .satisfies(meta -> {
                    assertThat(meta.getCheckpointId()).isEqualTo("cp-1");
                    assertThat(meta.getL3Key()).isEqualTo("checkpoints/task-1/cp-1.kryo");
                });
    }

    @Test
    void getObjectSize_returnsZeroOnlyWhenObjectIsMissing() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        ErrorResponseException notFound = mock(ErrorResponseException.class);
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(notFound.errorResponse()).thenReturn(errorResponse);
        when(errorResponse.code()).thenReturn("NoSuchObject");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(notFound);

        assertThat(coldStore.getObjectSize("checkpoints/task-1/cp-1.kryo")).isZero();
    }

    @Test
    void getObjectSize_propagatesStatFailureInsteadOfPretendingMissing() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new IOException("simulated stat transport failure"));

        assertThatThrownBy(() -> coldStore.getObjectSize("checkpoints/task-1/cp-1.kryo"))
                .isInstanceOf(CheckpointStoreException.class)
                .satisfies(ex -> assertThat(((CheckpointStoreException) ex).getFailureType())
                        .isEqualTo(CheckpointStoreException.FailureType.METADATA_READ_FAILED))
                .hasMessageContaining("MinIO stat failed");
    }

    @Test
    void loadCheckpoint_propagatesStorageReadFailureInsteadOfPretendingMissing() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new IOException("simulated transport failure"));

        assertThatThrownBy(() -> coldStore.loadCheckpoint("task-1/cp-1"))
                .isInstanceOf(CheckpointStoreException.class)
                .satisfies(ex -> assertThat(((CheckpointStoreException) ex).getFailureType())
                        .isEqualTo(CheckpointStoreException.FailureType.READ_FAILED))
                .hasMessageContaining("MinIO binary get failed");
    }

    @Test
    void loadCheckpoint_propagatesMinioServerFailureInsteadOfPretendingMissing() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new ServerException("simulated server failure", 500, "req-123"));

        assertThatThrownBy(() -> coldStore.loadCheckpoint("task-1/cp-1"))
                .isInstanceOf(CheckpointStoreException.class)
                .satisfies(ex -> assertThat(((CheckpointStoreException) ex).getFailureType())
                        .isEqualTo(CheckpointStoreException.FailureType.READ_FAILED))
                .hasMessageContaining("MinIO binary get failed");
    }

    @Test
    void loadCheckpoint_propagatesCorruptCheckpointPayloadInsteadOfPretendingMissing() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(getObjectResponse("not-a-valid-checkpoint".getBytes()));

        assertThatThrownBy(() -> coldStore.loadCheckpoint("task-1/cp-1"))
                .isInstanceOf(CheckpointStoreException.class)
                .satisfies(ex -> assertThat(((CheckpointStoreException) ex).getFailureType())
                        .isEqualTo(CheckpointStoreException.FailureType.PAYLOAD_INVALID))
                .hasMessageContaining("Checkpoint payload invalid");
    }

    @Test
    void loadCheckpoint_propagatesVersionMismatchAsTypedFailure() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        TaskState state = TaskState.builder()
                .taskId("task-1")
                .description("checkpoint")
                .build();
        byte[] payload = new KryoSerializer().serializeCompressed(state);
        byte[] currentRaw = gunzip(payload);
        currentRaw[4] = (byte) 99;

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(getObjectResponse(gzip(currentRaw)));

        assertThatThrownBy(() -> coldStore.loadCheckpoint("task-1/cp-1"))
                .isInstanceOf(CheckpointStoreException.class)
                .satisfies(ex -> assertThat(((CheckpointStoreException) ex).getFailureType())
                        .isEqualTo(CheckpointStoreException.FailureType.VERSION_MISMATCH))
                .hasMessageContaining("expected=1")
                .hasMessageContaining("actual=99");
    }

    @Test
    void retrieveFragment_propagatesMinioServerFailureInsteadOfPretendingMissing() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new ServerException("simulated server failure", 500, "req-456"));

        assertThatThrownBy(() -> coldStore.retrieveFragment("frag-1"))
                .isInstanceOf(CheckpointStoreException.class)
                .satisfies(ex -> assertThat(((CheckpointStoreException) ex).getFailureType())
                        .isEqualTo(CheckpointStoreException.FailureType.READ_FAILED))
                .hasMessageContaining("MinIO get failed");
    }

    @Test
    void listCheckpointMetadata_propagatesStatFailureInsteadOfSynthesizingMetadata() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioColdStore coldStore = new MinioColdStore(minioClient, "vortex-it", "run-123/");

        @SuppressWarnings("unchecked")
        Result<Item> dataResult = mock(Result.class);
        Item dataItem = mock(Item.class);
        when(dataItem.objectName()).thenReturn("run-123/checkpoints/task-1/cp-1.kryo");
        when(dataResult.get()).thenReturn(dataItem);
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(dataResult));
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new IOException("simulated stat failure"));

        assertThatThrownBy(() -> coldStore.listCheckpointMetadata("task-1"))
                .isInstanceOf(CheckpointStoreException.class)
                .satisfies(ex -> assertThat(((CheckpointStoreException) ex).getFailureType())
                        .isEqualTo(CheckpointStoreException.FailureType.METADATA_READ_FAILED))
                .hasMessageContaining("MinIO stat failed");
    }

    private static byte[] serialize(MinioColdStore coldStore, TaskState state) {
        String checkpointId = UUID.randomUUID().toString();
        state.setLatestCheckpointId(checkpointId);
        return new KryoSerializer().serializeCompressed(state);
    }

    private static GetObjectResponse getObjectResponse(byte[] payload) {
        return new GetObjectResponse(
                Headers.of(),
                "vortex-it",
                null,
                "key",
                new ByteArrayInputStream(payload)
        );
    }

    private static PutObjectArgs argThatPutObject(String bucket, String object) {
        return org.mockito.ArgumentMatchers.argThat(args ->
                bucket.equals(args.bucket()) && object.equals(args.object()));
    }

    private static byte[] gunzip(byte[] payload) throws IOException {
        try (java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(payload))) {
            return gzis.readAllBytes();
        }
    }

    private static byte[] gzip(byte[] payload) throws IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(bos)) {
            gzos.write(payload);
            gzos.finish();
            return bos.toByteArray();
        }
    }

    private static GetObjectArgs argThatGetObject(String bucket, String object) {
        return org.mockito.ArgumentMatchers.argThat(args ->
                bucket.equals(args.bucket()) && object.equals(args.object()));
    }

    private static ListObjectsArgs argThatListObjects(String bucket, String prefix) {
        return org.mockito.ArgumentMatchers.argThat(args ->
                bucket.equals(args.bucket()) && prefix.equals(args.prefix()));
    }

    private static RemoveObjectArgs argThatRemoveObject(String bucket, String object) {
        return org.mockito.ArgumentMatchers.argThat(args ->
                bucket.equals(args.bucket()) && object.equals(args.object()));
    }
}
