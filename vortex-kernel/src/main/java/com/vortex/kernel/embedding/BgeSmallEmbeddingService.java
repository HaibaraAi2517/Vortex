package com.vortex.kernel.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.vortex.common.exception.EmbeddingException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Local embedding service using BGE-Small-ZH (BAAI/bge-small-zh-v1.5).
 *
 * This implementation intentionally avoids DJL Predictor/Translator on Windows
 * and uses the ONNX Runtime Java API directly.
 */
@Slf4j
@Service("bgeSmallEmbeddingService")
public class BgeSmallEmbeddingService implements EmbeddingService, TokenCounter {

    private static final int DIMENSION = 512;
    private static final int MAX_SEQ_LEN = 512;
    private static final String RETRIEVAL_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private static final int BATCH_SIZE = 32;

    private final String modelPath;
    private final boolean safeHashMode;
    private final ExecutorService jniPool;

    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public BgeSmallEmbeddingService(String modelPath) {
        this(modelPath, false);
    }

    @Autowired
    public BgeSmallEmbeddingService(
            @Value("${vortex.kernel.embedding.bge.model-path:}") String modelPath,
            @Value("${vortex.kernel.embedding.bge.safe-hash-mode:false}") boolean safeHashMode) {
        this.modelPath = modelPath;
        this.safeHashMode = safeHashMode;
        this.jniPool = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                Thread.ofPlatform().name("onnx-jni-", 0).factory());
    }

    @PostConstruct
    public void init() throws Exception {
        log.info("Loading BGE-Small embedding model (dim={})...", DIMENSION);

        Path base = requireModelBase();
        Path tokenizerPath = base.resolve("tokenizer.json");
        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
        log.info("Tokenizer loaded from: {}", tokenizerPath);

        if (safeHashMode) {
            log.warn("BGE safe-hash mode enabled: using tokenizer-based hashed embeddings instead of ONNX inference");
            return;
        }

        Path modelFile = resolveModelFile(base);
        environment = OrtEnvironment.getEnvironment();
        session = environment.createSession(modelFile.toString(), createSessionOptions());
        log.info("BGE-Small model loaded successfully from: {}", modelFile);
    }

    @PreDestroy
    public void close() {
        jniPool.shutdown();
        closeQuietly(session, "session");
        session = null;
        // OrtEnvironment is a process-global singleton in the Java binding. Do not close it here.
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
    }

    @Override
    public float[] embed(String text) {
        try {
            if (safeHashMode) {
                return normalizeAndValidateEmbedding(hashEmbedding(text), "BGE safe-hash embed");
            }
            String input = buildRetrievalInput(text);
            return normalizeAndValidateEmbedding(predictSingle(input), "BGE embed");
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Embedding failed for text (len={}): {}", text == null ? 0 : text.length(), e.getMessage());
            throw new EmbeddingException("BGE embed failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> results = new ArrayList<>(texts.size());
        List<String> prefixedTexts = texts.stream()
                .map(BgeSmallEmbeddingService::buildRetrievalInput)
                .toList();

        for (int offset = 0; offset < prefixedTexts.size(); offset += BATCH_SIZE) {
            int end = Math.min(offset + BATCH_SIZE, prefixedTexts.size());
            List<String> subBatch = prefixedTexts.subList(offset, end);
            List<float[]> batchResults;
            try {
                batchResults = predictBatch(subBatch);
            } catch (Exception e) {
                log.warn("Batch predict failed for sub-batch size={}, falling back to sequential: {}",
                        subBatch.size(), e.getMessage());
                for (String text : subBatch) {
                    try {
                        results.add(normalizeAndValidateEmbedding(predictSingle(text), "BGE sequential fallback"));
                    } catch (EmbeddingException e2) {
                        throw e2;
                    } catch (Exception e2) {
                        log.error("Sequential fallback embed failed: {}", e2.getMessage());
                        throw new EmbeddingException("Sequential fallback also failed", e2);
                    }
                }
                continue;
            }
            for (float[] raw : batchResults) {
                results.add(normalizeAndValidateEmbedding(raw, "BGE batch embed"));
            }
        }
        return results;
    }

    public CompletableFuture<float[]> embedAsync(String text) {
        return CompletableFuture.supplyAsync(() -> embed(text), jniPool);
    }

    public CompletableFuture<List<float[]>> embedBatchAsync(List<String> texts) {
        return CompletableFuture.supplyAsync(() -> embedBatch(texts), jniPool);
    }

    int jniPoolActiveCountForTest() {
        return jniPool instanceof ThreadPoolExecutor executor ? executor.getActiveCount() : -1;
    }

    int jniPoolMaxSizeForTest() {
        return jniPool instanceof ThreadPoolExecutor executor ? executor.getMaximumPoolSize() : -1;
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return tokenizer.encode(text).getIds().length;
    }

    public static float[] computeCentroid(List<float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return new float[DIMENSION];
        }
        int dim = embeddings.get(0).length;
        float[] centroid = new float[dim];
        int count = 0;
        for (float[] emb : embeddings) {
            if (emb == null) {
                continue;
            }
            for (int i = 0; i < dim; i++) {
                centroid[i] += emb[i];
            }
            count++;
        }
        if (count == 0) {
            return centroid;
        }
        for (int i = 0; i < dim; i++) {
            centroid[i] /= count;
        }
        return l2Normalize(centroid);
    }

    public static String buildRetrievalInput(String text) {
        return RETRIEVAL_PREFIX + (text == null ? "" : text);
    }

    List<float[]> predictBatch(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<Encoding> encodings = texts.stream().map(tokenizer::encode).toList();
        return runOrt(encodings);
    }

    float[] predictSingle(String input) throws Exception {
        List<float[]> result = runOrt(List.of(tokenizer.encode(input)));
        if (result.isEmpty()) {
            throw new IllegalStateException("BGE single predict returned no outputs");
        }
        return result.getFirst();
    }

    static boolean isZeroVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return true;
        }
        for (float value : vector) {
            if (Float.isFinite(value) && Math.abs(value) > 1.0e-12f) {
                return false;
            }
        }
        return true;
    }

    static float[] normalizeAndValidateEmbedding(float[] raw, String context) {
        if (raw == null || raw.length == 0) {
            throw new EmbeddingException(context + " returned an empty embedding");
        }
        float[] normalized = l2Normalize(raw);
        if (isZeroVector(normalized)) {
            throw new EmbeddingException(context + " returned a zero vector");
        }
        return normalized;
    }

    private static float[] l2Normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return vector;
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    private float[] hashEmbedding(String text) {
        Encoding encoding = tokenizer.encode(text == null ? "" : text);
        long[] tokenIds = encoding.getIds();
        if (tokenIds.length == 0) {
            throw new EmbeddingException("BGE safe-hash embed returned no tokens");
        }
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < tokenIds.length; i++) {
            long tokenId = tokenIds[i];
            int primary = Math.floorMod(Long.hashCode(tokenId), DIMENSION);
            int secondary = Math.floorMod(Long.hashCode((tokenId * 31L) + i), DIMENSION);
            vector[primary] += 1.0f;
            vector[secondary] += 0.5f;
        }
        return vector;
    }

    private List<float[]> runOrt(List<Encoding> encodings) throws Exception {
        if (session == null || environment == null) {
            throw new IllegalStateException("BGE ONNX session is not initialized");
        }
        long[][] inputIds = new long[encodings.size()][MAX_SEQ_LEN];
        long[][] attentionMask = new long[encodings.size()][MAX_SEQ_LEN];
        long[][] tokenTypeIds = new long[encodings.size()][MAX_SEQ_LEN];
        for (int i = 0; i < encodings.size(); i++) {
            fillPadded(encodings.get(i).getIds(), inputIds[i]);
            fillPadded(encodings.get(i).getAttentionMask(), attentionMask[i]);
            fillPadded(encodings.get(i).getTypeIds(), tokenTypeIds[i]);
        }

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, inputIds);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, attentionMask);
             OnnxTensor typeIdsTensor = OnnxTensor.createTensor(environment, tokenTypeIds)) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", idsTensor);
            inputs.put("attention_mask", maskTensor);
            inputs.put("token_type_ids", typeIdsTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                return extractEmbeddings(result, encodings.size());
            }
        }
    }

    private static List<float[]> extractEmbeddings(OrtSession.Result result, int expectedBatchSize) throws OrtException {
        OnnxValue output = result.get(0);
        Object value = output.getValue();
        if (!(value instanceof float[][][] tensor)) {
            throw new IllegalStateException("Unexpected ORT output type: " + value.getClass());
        }
        if (tensor.length != expectedBatchSize) {
            throw new IllegalStateException(
                    "Unexpected ORT batch size: " + tensor.length + " != " + expectedBatchSize);
        }
        List<float[]> embeddings = new ArrayList<>(tensor.length);
        for (float[][] batchItem : tensor) {
            if (batchItem.length == 0 || batchItem[0].length == 0) {
                throw new IllegalStateException("BGE output tensor is empty");
            }
            embeddings.add(Arrays.copyOf(batchItem[0], batchItem[0].length));
        }
        return embeddings;
    }

    private static void fillPadded(long[] raw, long[] target) {
        if (raw == null || raw.length == 0) {
            return;
        }
        System.arraycopy(raw, 0, target, 0, Math.min(raw.length, target.length));
    }

    private Path requireModelBase() {
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalStateException(
                    "BGE model path not configured. " +
                    "Download tokenizer.json and model.onnx from " +
                    "https://hf-mirror.com/BAAI/bge-small-zh-v1.5 " +
                    "and set vortex.kernel.embedding.bge.model-path=<dir> in application.yml");
        }
        return Paths.get(modelPath);
    }

    private static Path resolveModelFile(Path base) {
        List<Path> candidates = List.of(
                base.resolve("model.onnx"),
                base.resolve("onnx").resolve("model.onnx"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "model.onnx not found. Expected at: " + base.resolve("model.onnx") +
                        " or " + base.resolve("onnx/model.onnx"));
    }

    private static OrtSession.SessionOptions createSessionOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(1);
        options.setMemoryPatternOptimization(false);
        options.setCPUArenaAllocator(true);
        options.addCPU(true);
        return options;
    }

    private static void closeQuietly(AutoCloseable closeable, String label) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Failed to close BGE {} cleanly: {}", label, e.getMessage());
        }
    }
}
