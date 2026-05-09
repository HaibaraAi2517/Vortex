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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

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

    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;
    private HuggingFaceTokenizer tokenizer;

    public BgeSmallEmbeddingService(
            @Value("${vortex.kernel.embedding.bge.model-path:}") String modelPath) {
        this.modelPath = modelPath;
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
        if (predictor != null) predictor.close();
        if (model != null) model.close();
        if (tokenizer != null) tokenizer.close();
    }

    @Override
    public float[] embed(String text) {
        try {
            // BGE models perform better with the instruction prefix for retrieval
            String input = "为这个句子生成表示以用于检索相关文章：" + text;
            float[] raw = predictor.predict(input);
            return l2Normalize(raw);
        } catch (Exception e) {
            log.error("Embedding failed for text (len={}): {}", text.length(), e.getMessage());
            // Return zero vector on failure — caller should handle gracefully
            return new float[DIMENSION];
        }
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

    // ---- DJL Translator ----

    /**
     * Translates a String → ONNX input tensors → float[] embedding.
     * BGE-Small uses the [CLS] token representation as the sentence embedding.
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

            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] tokenTypeIds = encoding.getTypeIds();

            // Truncate to MAX_SEQ_LEN
            int len = Math.min(inputIds.length, MAX_SEQ_LEN);
            inputIds = Arrays.copyOf(inputIds, len);
            attentionMask = Arrays.copyOf(attentionMask, len);
            tokenTypeIds = Arrays.copyOf(tokenTypeIds, len);

            Shape shape = new Shape(1, len);
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
            return null; // single-item inference
        }
    }
}
