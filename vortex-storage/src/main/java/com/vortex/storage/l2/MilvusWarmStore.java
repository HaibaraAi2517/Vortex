package com.vortex.storage.l2;

import com.vortex.common.model.MemoryFragment;
import com.vortex.storage.api.L2WarmStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.DataType;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.grpc.QueryResults;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * L2 Warm Store backed by Milvus.
 *
 * Collection schema:
 *   id        VARCHAR(64)  PK
 *   namespace VARCHAR(128)
 *   content   VARCHAR(65535)
 *   embedding FLOAT_VECTOR(dim)
 *   importance FLOAT
 *   token_count INT32
 */
@Slf4j
@Component
public class MilvusWarmStore implements L2WarmStore {

    private static final String DEFAULT_COLLECTION = "vortex_memory";
    private static final String FIELD_ID = "id";
    private static final String FIELD_NS = "namespace";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_IMPORTANCE = "importance";
    private static final String FIELD_TOKEN_COUNT = "token_count";

    private final MilvusServiceClient client;
    private final int embeddingDim;
    private final String collectionName;
    private final boolean dropCollectionOnStartup;
    private final String dropCollectionConfirmToken;

    public MilvusWarmStore(
            @Value("${vortex.storage.l2.milvus.host:localhost}") String host,
            @Value("${vortex.storage.l2.milvus.port:19530}") int port,
            @Value("${vortex.storage.l2.embedding-dim:512}") int embeddingDim,
            @Value("${vortex.storage.l2.milvus.collection:" + DEFAULT_COLLECTION + "}") String collectionName,
            @Value("${vortex.storage.l2.milvus.drop-collection-on-startup:false}") boolean dropCollectionOnStartup,
            @Value("${vortex.storage.l2.milvus.drop-collection-confirm-token:}") String dropCollectionConfirmToken) {
        this.embeddingDim = embeddingDim;
        this.collectionName = collectionName;
        this.dropCollectionOnStartup = dropCollectionOnStartup;
        this.dropCollectionConfirmToken = dropCollectionConfirmToken;
        this.client = new MilvusServiceClient(
                ConnectParam.newBuilder().withHost(host).withPort(port).build());
    }

    @PostConstruct
    public void init() {
        R<Boolean> hasCollection = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
        boolean exists = Boolean.TRUE.equals(hasCollection.getData());

        if (exists && dropCollectionOnStartup) {
            if (!"I-KNOW-WHAT-I-AM-DOING".equals(dropCollectionConfirmToken)) {
                log.error("drop-collection-on-startup=true but confirm token missing or invalid; refusing to drop '{}'", collectionName);
                throw new IllegalStateException(
                        "Set MILVUS_DROP_CONFIRM_TOKEN=I-KNOW-WHAT-I-AM-DOING to confirm collection drop");
            }
            log.warn("drop-collection-on-startup=true: dropping Milvus collection '{}' for dim migration", collectionName);
            client.dropCollection(
                    DropCollectionParam.newBuilder()
                            .withCollectionName(collectionName).build());
            exists = false;
        }

        if (!exists) {
            createCollection();
            createIndex();
            loadCollection();
            log.info("Milvus collection '{}' created with dim={}", collectionName, embeddingDim);
        } else {
            validateCollectionDimension();
            loadCollection();
            log.info("Milvus collection '{}' already exists (dim={})", collectionName, embeddingDim);
        }
    }

    @PreDestroy
    void close() {
        client.close();
    }

    private void createCollection() {
        CreateCollectionParam.Builder builder = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName);
        builder.addFieldType(FieldType.newBuilder().withName(FIELD_ID).withDataType(DataType.VarChar)
                .withMaxLength(256).withPrimaryKey(true).withAutoID(false).build());
        builder.addFieldType(FieldType.newBuilder().withName(FIELD_NS).withDataType(DataType.VarChar)
                .withMaxLength(128).build());
        builder.addFieldType(FieldType.newBuilder().withName(FIELD_CONTENT).withDataType(DataType.VarChar)
                .withMaxLength(65535).build());
        builder.addFieldType(FieldType.newBuilder().withName(FIELD_EMBEDDING).withDataType(DataType.FloatVector)
                .withDimension(embeddingDim).build());
        builder.addFieldType(FieldType.newBuilder().withName(FIELD_IMPORTANCE).withDataType(DataType.Float).build());
        builder.addFieldType(FieldType.newBuilder().withName(FIELD_TOKEN_COUNT).withDataType(DataType.Int32).build());
        client.createCollection(builder.build());
    }

    private void createIndex() {
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(FIELD_EMBEDDING)
                .withIndexType(io.milvus.param.IndexType.IVF_FLAT)
                .withMetricType(io.milvus.param.MetricType.COSINE)
                .withExtraParam("{\"nlist\":128}")
                .build());
    }

    private void loadCollection() {
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
    }

    @Override
    public void upsert(MemoryFragment fragment) {
        // Prefer l2Embedding (DeepSeek) when available; fall back to BGE-Small embedding
        float[] embeddingToStore = (fragment.getL2Embedding() != null)
                ? fragment.getL2Embedding()
                : fragment.getEmbedding();

        if (embeddingToStore == null) {
            log.warn("Skipping L2 upsert for fragment id={}: no embedding", fragment.getId());
            return;
        }
        List<UpsertParam.Field> fields = List.of(
                new UpsertParam.Field(FIELD_ID, List.of(fragment.getId())),
                new UpsertParam.Field(FIELD_NS, List.of(nullToEmpty(fragment.getNamespace()))),
                new UpsertParam.Field(FIELD_CONTENT, List.of(fragment.getContent())),
                new UpsertParam.Field(FIELD_EMBEDDING, List.of(toFloatList(embeddingToStore))),
                new UpsertParam.Field(FIELD_IMPORTANCE, List.of((float) fragment.getImportance())),
                new UpsertParam.Field(FIELD_TOKEN_COUNT, List.of(fragment.getTokenCount()))
        );
        R<MutationResult> result = client.upsert(
                UpsertParam.newBuilder().withCollectionName(collectionName).withFields(fields).build());
        if (result.getStatus() != 0) {
            log.error("Milvus upsert failed for id={}: {}", fragment.getId(), result.getMessage());
            throw new IllegalStateException("Milvus upsert failed for fragment " + fragment.getId()
                    + ": " + result.getMessage());
        }
    }

    @Override
    public List<MemoryFragment> search(float[] queryEmbedding, String namespace, int topK) {
        if (queryEmbedding == null) {
            log.warn("L2 search called with null embedding — skipping");
            return Collections.emptyList();
        }
        String expr = FIELD_NS + " == \"" + namespace + "\"";
        R<SearchResults> result = client.search(SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(io.milvus.param.MetricType.COSINE)
                .withTopK(topK)
                .withFloatVectors(List.of(toFloatList(queryEmbedding)))
                .withVectorFieldName(FIELD_EMBEDDING)
                .withExpr(expr)
                .withOutFields(List.of(FIELD_ID, FIELD_NS, FIELD_CONTENT,
                        FIELD_IMPORTANCE, FIELD_TOKEN_COUNT))
                .build());

        if (result.getStatus() != 0 || result.getData() == null) {
            log.error("Milvus search failed: {}", result.getMessage());
            return Collections.emptyList();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
        List<MemoryFragment> fragments = new ArrayList<>();
        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            SearchResultsWrapper.IDScore score = wrapper.getIDScore(0).get(i);
            MemoryFragment f = MemoryFragment.builder()
                    .id(score.getStrID())
                    .namespace(namespace)
                    .content((String) wrapper.getFieldWrapper(FIELD_CONTENT).getFieldData().get(i))
                    .importance(((Number) wrapper.getFieldWrapper(FIELD_IMPORTANCE).getFieldData().get(i)).doubleValue())
                    .tokenCount(((Number) wrapper.getFieldWrapper(FIELD_TOKEN_COUNT).getFieldData().get(i)).intValue())
                    .lastAccessTime(System.currentTimeMillis())
                    .build();
            fragments.add(f);
        }
        return fragments;
    }

    @Override
    public Optional<MemoryFragment> get(String id) {
        R<QueryResults> result = client.query(QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(FIELD_ID + " in [\"" + id + "\"]")
                .withOutFields(List.of(FIELD_ID, FIELD_NS, FIELD_CONTENT,
                        FIELD_IMPORTANCE, FIELD_TOKEN_COUNT))
                .build());
        if (result.getStatus() != 0 || result.getData() == null) {
            log.warn("Milvus get failed for id={}: {}", id, result.getMessage());
            return Optional.empty();
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(result.getData());
        List<?> ids = wrapper.getFieldWrapper(FIELD_ID).getFieldData();
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }
        MemoryFragment f = MemoryFragment.builder()
                .id((String) ids.get(0))
                .namespace((String) wrapper.getFieldWrapper(FIELD_NS).getFieldData().get(0))
                .content((String) wrapper.getFieldWrapper(FIELD_CONTENT).getFieldData().get(0))
                .importance(((Number) wrapper.getFieldWrapper(FIELD_IMPORTANCE).getFieldData().get(0)).doubleValue())
                .tokenCount(((Number) wrapper.getFieldWrapper(FIELD_TOKEN_COUNT).getFieldData().get(0)).intValue())
                .lastAccessTime(System.currentTimeMillis())
                .build();
        return Optional.of(f);
    }

    @Override
    public void delete(String id) {
        client.delete(io.milvus.param.dml.DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(FIELD_ID + " in [\"" + id + "\"]")
                .build());
    }

    @Override
    public int vectorDimension() {
        return embeddingDim;
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private void validateCollectionDimension() {
        R<DescribeCollectionResponse> result = client.describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (result.getStatus() != 0 || result.getData() == null) {
            throw new IllegalStateException(
                    "Failed to describe Milvus collection '" + collectionName + "': " + result.getMessage());
        }

        int actualDim = result.getData().getSchema().getFieldsList().stream()
                .filter(field -> FIELD_EMBEDDING.equals(field.getName()))
                .findFirst()
                .map(this::extractDimension)
                .orElseThrow(() -> new IllegalStateException(
                        "Milvus collection '" + collectionName + "' is missing embedding field '" + FIELD_EMBEDDING + "'"));

        if (actualDim != embeddingDim) {
            throw new IllegalStateException(
                    "Milvus collection '" + collectionName + "' dimension mismatch: configured="
                            + embeddingDim + ", actual=" + actualDim
                            + ". Align vortex.storage.l2.embedding-dim or recreate the collection "
                            + "(for one-time migration set vortex.storage.l2.milvus.drop-collection-on-startup=true).");
        }
    }

    private int extractDimension(FieldSchema field) {
        return field.getTypeParamsList().stream()
                .filter(param -> "dim".equals(param.getKey()))
                .findFirst()
                .map(KeyValuePair::getValue)
                .map(Integer::parseInt)
                .orElseThrow(() -> new IllegalStateException(
                        "Milvus field '" + FIELD_EMBEDDING + "' is missing type param 'dim'"));
    }
}
