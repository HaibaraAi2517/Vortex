package com.vortex.kernel.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.vortex.common.exception.EmbeddingException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Local embedding service using BGE-Small-ZH (BAAI/bge-small-zh-v1.5).
 *
 * Model: BAAI/bge-small-zh-v1.5
 * Dimension: 512
 * Inference: ONNX Runtime via DJL — no GPU required, ~5-15 ms per call on CPU
 *
 * First-run model download:
 *   DJL will automatically download the ONNX model from HuggingFace Hub
 *   into the DJL cache directory (~/.djl.ai/cache/).
 *   Requires internet access on first startup only.
 *
 * Alternatively, set vortex.kernel.embedding.bge.model-path to a local
 * directory containing model.onnx + tokenizer.json to run fully offline.
 */
@Slf4j
@Service("bgeSmallEmbeddingService")
public class BgeSmallEmbeddingService implements EmbeddingService, TokenCounter {

    private static final int DIMENSION = 512;
    private static final int MAX_SEQ_LEN = 512;

    private final String modelPath;
    private final ExecutorService jniPool;

    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;
    private HuggingFaceTokenizer tokenizer;

    public BgeSmallEmbeddingService(
            @Value("${vortex.kernel.embedding.bge.model-path:}") String modelPath) {
        this.modelPath = modelPath;
        this.jniPool = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                Thread.ofPlatform().name("onnx-jni-", 0).factory());
    }

    @PostConstruct
    public void init() throws Exception {
        log.info("Loading BGE-Small embedding model (dim={})...", DIMENSION);

        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalStateException(
                    "BGE model path not configured. " +
                    "Download tokenizer.json and model.onnx from " +
                    "https://hf-mirror.com/BAAI/bge-small-zh-v1.5 " +
                    "and set vortex.kernel.embedding.bge.model-path=<dir> in application.yml");
        }

        Path base = Paths.get(modelPath);

        // Load tokenizer from local file
        Path tokenizerPath = base.resolve("tokenizer.json");
        if (!tokenizerPath.toFile().exists()) {
            throw new IllegalStateException("tokenizer.json not found at: " + tokenizerPath);
        }
        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
        log.info("Tokenizer loaded from: {}", tokenizerPath);

        // Resolve model.onnx — support both flat and onnx/ subdirectory layouts
        Path modelFile = base.resolve("model.onnx");
        if (!modelFile.toFile().exists()) {
            modelFile = base.resolve("onnx/model.onnx");
        }
        if (!modelFile.toFile().exists()) {
            throw new IllegalStateException(
                    "model.onnx not found. Expected at: " + base.resolve("model.onnx") +
                    " or " + base.resolve("onnx/model.onnx"));
        }
        // DJL needs the directory containing the .onnx file
        Path modelDir = modelFile.getParent();

        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(modelDir)
                .optModelName("model")   // loads model.onnx
                .optTranslator(new BgeTranslator(tokenizer))
                .optEngine("OnnxRuntime")
                .build();

        model = criteria.loadModel();
        predictor = model.newPredictor();
        log.info("BGE-Small model loaded successfully from: {}", modelDir);
    }

    @PreDestroy
    public void close() {
        jniPool.shutdown();
        if (predictor != null) predictor.close();
        if (model != null) model.close();
        if (tokenizer != null) tokenizer.close();
    }

    @Override
    public float[] embed(String text) {
        try {
            // BGE models perform better with the instruction prefix for retrieval
            String input = "为这个句子生成表示以用于检索相关文章：" + text;
            return normalizeAndValidateEmbedding(predictSingle(input), "BGE embed");
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Embedding failed for text (len={}): {}", text == null ? 0 : text.length(), e.getMessage());
            throw new EmbeddingException("BGE embed failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch embedding using DJL batch predict with Batchifier.STACK.
     * Groups inputs into sub-batches of {@link #BATCH_SIZE} to bound memory usage.
     * Falls back to sequential embed() if batch predict fails.
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> results = new ArrayList<>(texts.size());
        // Prepend instruction prefix
        List<String> prefixedTexts = texts.stream()
                .map(t -> "为这个句子生成表示以用于检索相关文章：" + t)
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
                // Fallback: sequential
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

    /**
     * Compute the centroid (mean vector, L2-normalized) of a list of embeddings.
     */
    public static float[] computeCentroid(List<float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return new float[DIMENSION];
        }
        int dim = embeddings.get(0).length;
        float[] centroid = new float[dim];
        int count = 0;
        for (float[] emb : embeddings) {
            if (emb == null) continue;
            for (int i = 0; i < dim; i++) {
                centroid[i] += emb[i];
            }
            count++;
        }
        if (count == 0) return centroid;
        for (int i = 0; i < dim; i++) {
            centroid[i] /= count;
        }
        return l2Normalize(centroid);
    }

    List<float[]> predictBatch(List<String> texts) throws Exception {
        return predictor.batchPredict(texts);
    }

    float[] predictSingle(String input) throws Exception {
        return predictor.predict(input);
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

    /** L2-normalise a vector so cosine similarity == dot product. */
    private static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    private static final int BATCH_SIZE = 32;

    // ---- DJL Translator ----

    /**
     * Translates String → ONNX input tensors → float[] embedding.
     * BGE-Small uses the [CLS] token representation as the sentence embedding.
     *
     * Batch mode: inputs are padded to MAX_SEQ_LEN so Batchifier.STACK can stack
     * them along dim 0. DJL splits the output before calling processOutput, so
     * each call still sees a single [1, MAX_SEQ_LEN, hidden] tensor.
     */
    private static final class BgeTranslator implements Translator<String, float[]> {

        private final HuggingFaceTokenizer tokenizer;

        BgeTranslator(HuggingFaceTokenizer tokenizer) {
            this.tokenizer = tokenizer;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            Encoding encoding = tokenizer.encode(input);
            NDManager manager = ctx.getNDManager();

            long[] rawIds = encoding.getIds();
            long[] rawMask = encoding.getAttentionMask();
            long[] rawTypeIds = encoding.getTypeIds();

            // Pad to exactly MAX_SEQ_LEN for batch stacking compatibility.
            // Zero-padding: pad token = 0, attention mask = 0.
            long[] inputIds = new long[MAX_SEQ_LEN];
            long[] attentionMask = new long[MAX_SEQ_LEN];
            long[] tokenTypeIds = new long[MAX_SEQ_LEN];

            int len = Math.min(rawIds.length, MAX_SEQ_LEN);
            System.arraycopy(rawIds, 0, inputIds, 0, len);
            System.arraycopy(rawMask, 0, attentionMask, 0, len);
            System.arraycopy(rawTypeIds, 0, tokenTypeIds, 0, len);

            Shape shape = new Shape(1, MAX_SEQ_LEN);
            NDArray ids = manager.create(inputIds, shape).toType(DataType.INT64, false);
            NDArray mask = manager.create(attentionMask, shape).toType(DataType.INT64, false);
            NDArray typeIds = manager.create(tokenTypeIds, shape).toType(DataType.INT64, false);

            ids.setName("input_ids");
            mask.setName("attention_mask");
            typeIds.setName("token_type_ids");

            return new NDList(ids, mask, typeIds);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            // Avoid NDArray slicing here: on Windows the cross-engine fallback path
            // can jump into Rust-native tensor conversion and crash the JVM.
            // Read the dense output once and slice the CLS token in plain Java.
            // DJL splits batch output before this call, so shape is always [1, N, hidden].
            NDArray lastHiddenState = list.get(0);
            long[] dims = lastHiddenState.getShape().getShape();
            if (dims.length != 3 || dims[0] != 1L) {
                throw new IllegalStateException(
                        "Unexpected BGE output shape: " + lastHiddenState.getShape());
            }

            int hiddenSize = Math.toIntExact(dims[2]);
            float[] flat = lastHiddenState.toFloatArray();
            if (flat.length < hiddenSize) {
                throw new IllegalStateException(
                        "BGE output tensor too small: " + flat.length + " < " + hiddenSize);
            }
            return Arrays.copyOf(flat, hiddenSize);
        }

        @Override
        public Batchifier getBatchifier() {
            return Batchifier.STACK;
        }
    }
}
