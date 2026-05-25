package com.vortex.app.integration.support;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class IsolatedIntegrationTestSupport {

    private static final String TEST_BUCKET = "vortex-it";
    private static final String TEST_COLLECTION_PREFIX = "vortex_memory_it_";
    private static final String PAGE_TABLE_KEY_PREFIX = "system/semantic-page-table-";

    private IsolatedIntegrationTestSupport() {
    }

    public static final class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            String runId = UUID.randomUUID().toString().substring(0, 8);
            Path rootDir = Path.of(System.getProperty("java.io.tmpdir"), "vortex-it", runId)
                    .toAbsolutePath()
                    .normalize();

            TestPropertyValues.of(
                    "vortex.test.run-id=" + runId,
                    "vortex.test.root-dir=" + normalizePath(rootDir),
                    "vortex.storage.l3.minio.bucket=" + TEST_BUCKET,
                    "vortex.storage.l3.minio.key-prefix=" + runId + "/",
                    "vortex.storage.l2.milvus.collection=" + TEST_COLLECTION_PREFIX + runId,
                    "vortex.storage.l2.milvus.drop-collection-on-startup=true",
                    "vortex.storage.l2.milvus.drop-collection-confirm-token=I-KNOW-WHAT-I-AM-DOING",
                    "vortex.kernel.paging.page-table-key=" + PAGE_TABLE_KEY_PREFIX + runId + ".bin",
                    "vortex.kernel.learning.shadow-persistence-path=" + normalizePath(rootDir.resolve("shadow-eval.json")),
                    "vortex.kernel.persistence.dlq.path=" + normalizePath(rootDir.resolve("dlq.jsonl")),
                    "vortex.kernel.persistence.processed-keys.path=" + normalizePath(rootDir.resolve("processed-keys.txt")),
                    "vortex.kernel.snapshot.wal.dir=" + normalizePath(rootDir.resolve("wal"))
            ).applyTo(applicationContext.getEnvironment());
        }

        private static String normalizePath(Path path) {
            return path.toString().replace('\\', '/');
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        IntegrationResourceCleaner integrationResourceCleaner(
                @Value("${vortex.storage.l3.minio.endpoint}") String minioEndpoint,
                @Value("${vortex.storage.l3.minio.access-key}") String minioAccessKey,
                @Value("${vortex.storage.l3.minio.secret-key}") String minioSecretKey,
                @Value("${vortex.storage.l3.minio.bucket}") String minioBucket,
                @Value("${vortex.storage.l3.minio.key-prefix}") String minioKeyPrefix,
                @Value("${vortex.storage.l2.milvus.host}") String milvusHost,
                @Value("${vortex.storage.l2.milvus.port}") int milvusPort,
                @Value("${vortex.storage.l2.milvus.collection}") String milvusCollection,
                @Value("${vortex.test.cleanup.external-stores:true}") boolean cleanupExternalStores,
                @Value("${vortex.test.root-dir}") String rootDir) {
            return new IntegrationResourceCleaner(
                    minioEndpoint,
                    minioAccessKey,
                    minioSecretKey,
                    minioBucket,
                    minioKeyPrefix,
                    milvusHost,
                    milvusPort,
                    milvusCollection,
                    cleanupExternalStores,
                    Path.of(rootDir));
        }
    }

    @Slf4j
    static final class IntegrationResourceCleaner implements DisposableBean {

        private final String minioEndpoint;
        private final String minioAccessKey;
        private final String minioSecretKey;
        private final String minioBucket;
        private final String minioKeyPrefix;
        private final String milvusHost;
        private final int milvusPort;
        private final String milvusCollection;
        private final boolean cleanupExternalStores;
        private final Path rootDir;

        IntegrationResourceCleaner(
                String minioEndpoint,
                String minioAccessKey,
                String minioSecretKey,
                String minioBucket,
                String minioKeyPrefix,
                String milvusHost,
                int milvusPort,
                String milvusCollection,
                boolean cleanupExternalStores,
                Path rootDir) {
            this.minioEndpoint = minioEndpoint;
            this.minioAccessKey = minioAccessKey;
            this.minioSecretKey = minioSecretKey;
            this.minioBucket = minioBucket;
            this.minioKeyPrefix = minioKeyPrefix;
            this.milvusHost = milvusHost;
            this.milvusPort = milvusPort;
            this.milvusCollection = milvusCollection;
            this.cleanupExternalStores = cleanupExternalStores;
            this.rootDir = rootDir;
        }

        @Override
        public void destroy() {
            List<Exception> failures = new ArrayList<>();
            if (cleanupExternalStores) {
                cleanupMinio(failures);
                cleanupMilvus(failures);
            } else {
                log.debug("Skipping external store cleanup for isolated integration test collection={}", milvusCollection);
            }
            cleanupLocalFiles(failures);
            if (!failures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException(
                        "Failed to clean isolated integration-test resources for collection '" + milvusCollection + "'");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }

        private void cleanupMinio(List<Exception> failures) {
            if (minioKeyPrefix == null || minioKeyPrefix.isBlank()) {
                return;
            }
            MinioClient minioClient = MinioClient.builder()
                    .endpoint(minioEndpoint)
                    .credentials(minioAccessKey, minioSecretKey)
                    .build();
            try {
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioBucket).build());
                if (!exists) {
                    return;
                }
                Iterable<Result<Item>> results = minioClient.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(minioBucket)
                                .prefix(minioKeyPrefix)
                                .recursive(true)
                                .build());
                for (Result<Item> result : results) {
                    Item item = result.get();
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(minioBucket)
                            .object(item.objectName())
                            .build());
                }
            } catch (Exception e) {
                failures.add(new IllegalStateException(
                        "MinIO cleanup failed for bucket '" + minioBucket + "' prefix '" + minioKeyPrefix + "'", e));
            }
        }

        private void cleanupMilvus(List<Exception> failures) {
            MilvusServiceClient client = null;
            try {
                client = new MilvusServiceClient(
                        ConnectParam.newBuilder().withHost(milvusHost).withPort(milvusPort).build());
                boolean exists = Boolean.TRUE.equals(client.hasCollection(
                        HasCollectionParam.newBuilder().withCollectionName(milvusCollection).build()).getData());
                if (exists) {
                    client.dropCollection(DropCollectionParam.newBuilder()
                            .withCollectionName(milvusCollection)
                            .build());
                }
            } catch (Exception e) {
                failures.add(new IllegalStateException(
                        "Milvus cleanup failed for collection '" + milvusCollection + "'", e));
            } finally {
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception e) {
                        failures.add(new IllegalStateException("Milvus client close failed", e));
                    }
                }
            }
        }

        private void cleanupLocalFiles(List<Exception> failures) {
            if (!Files.exists(rootDir)) {
                return;
            }
            try (var paths = Files.walk(rootDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                failures.add(new IllegalStateException("Local test-resource cleanup failed for " + rootDir, cause));
            } catch (IOException e) {
                failures.add(new IllegalStateException("Local test-resource cleanup failed for " + rootDir, e));
            }
        }
    }
}
