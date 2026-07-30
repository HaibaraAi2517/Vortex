package com.vortex.kernel.hmc;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Local Hugging Face tokenizer and ONNX Runtime Cross-Encoder provider. */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "vortex.kernel.recall.cross-encoder",
        name = "enabled",
        havingValue = "true")
public class OnnxCrossEncoderScoringService implements CrossEncoderScoringService {

    private static final String SHA256_PATTERN = "(?i)[0-9a-f]{64}";
    private static final String MODEL_FILE_NAME = "model.onnx";
    private static final String TOKENIZER_FILE_NAME = "tokenizer.json";

    private final String modelPath;
    private final String modelName;
    private final String modelVersion;
    private final String expectedModelSha256;
    private final String expectedTokenizerSha256;
    private final int batchSize;
    private final int maxSequenceLength;
    private final int intraOpThreads;
    private final Duration timeout;

    private ModelMetadata modelMetadata;
    private BatchScorer batchScorer;
    private ExecutorService inferenceExecutor;

    @Autowired
    public OnnxCrossEncoderScoringService(
            @Value("${vortex.kernel.recall.cross-encoder.model-path:}") String modelPath,
            @Value("${vortex.kernel.recall.cross-encoder.model:}") String modelName,
            @Value("${vortex.kernel.recall.cross-encoder.version:}") String modelVersion,
            @Value("${vortex.kernel.recall.cross-encoder.expected-model-sha256:}")
                    String expectedModelSha256,
            @Value("${vortex.kernel.recall.cross-encoder.expected-tokenizer-sha256:}")
                    String expectedTokenizerSha256,
            @Value("${vortex.kernel.recall.cross-encoder.batch-size:16}") int batchSize,
            @Value("${vortex.kernel.recall.cross-encoder.max-sequence-length:512}")
                    int maxSequenceLength,
            @Value("${vortex.kernel.recall.cross-encoder.intra-op-threads:8}") int intraOpThreads,
            @Value("${vortex.kernel.recall.cross-encoder.timeout-ms:5000}") long timeoutMs) {
        this.modelPath = modelPath;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.expectedModelSha256 = expectedModelSha256;
        this.expectedTokenizerSha256 = expectedTokenizerSha256;
        this.batchSize = batchSize;
        this.maxSequenceLength = maxSequenceLength;
        this.intraOpThreads = intraOpThreads;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    OnnxCrossEncoderScoringService(
            ModelMetadata modelMetadata,
            int batchSize,
            Duration timeout,
            BatchScorer batchScorer) {
        this.modelPath = null;
        this.modelName = modelMetadata == null ? null : modelMetadata.model();
        this.modelVersion = modelMetadata == null ? null : modelMetadata.version();
        this.expectedModelSha256 = modelMetadata == null ? null : modelMetadata.sha256();
        this.expectedTokenizerSha256 = null;
        this.batchSize = batchSize;
        this.maxSequenceLength = 512;
        this.intraOpThreads = 1;
        this.timeout = timeout;
        this.batchScorer = batchScorer;
        validateRuntimeConfiguration();
        this.modelMetadata = validateMetadata(modelMetadata);
        this.inferenceExecutor = createExecutor();
    }

    @PostConstruct
    public void init() {
        validateRuntimeConfiguration();
        requireNonBlank(modelPath, "Cross-encoder model path");
        requireNonBlank(modelName, "Cross-encoder model name");
        requireNonBlank(modelVersion, "Cross-encoder model version");
        validateExpectedSha256(expectedModelSha256, "model");
        validateExpectedSha256(expectedTokenizerSha256, "tokenizer");

        Path base = Paths.get(modelPath).toAbsolutePath().normalize();
        Path modelFile = requireRegularFile(base.resolve(MODEL_FILE_NAME), "Cross-encoder model");
        Path tokenizerFile = requireRegularFile(base.resolve(TOKENIZER_FILE_NAME), "Cross-encoder tokenizer");
        String actualModelSha256 = sha256(modelFile);
        String actualTokenizerSha256 = sha256(tokenizerFile);
        requireMatchingSha256(expectedModelSha256, actualModelSha256, "model");
        requireMatchingSha256(expectedTokenizerSha256, actualTokenizerSha256, "tokenizer");

        this.modelMetadata = validateMetadata(new ModelMetadata(
                modelName,
                modelVersion,
                actualModelSha256));
        try {
            this.batchScorer = new OnnxBatchScorer(
                    modelFile,
                    tokenizerFile,
                    maxSequenceLength,
                    intraOpThreads);
            this.inferenceExecutor = createExecutor();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
        log.info(
                "Cross-encoder initialized model={} version={} sha256={} batchSize={} maxSequenceLength={} "
                        + "intraOpThreads={} timeoutMs={}",
                modelName,
                modelVersion,
                actualModelSha256,
                batchSize,
                maxSequenceLength,
                intraOpThreads,
                timeout.toMillis());
    }

    @Override
    public ModelMetadata metadata() {
        if (modelMetadata == null) {
            throw new IllegalStateException("Cross-encoder provider is not initialized");
        }
        return modelMetadata;
    }

    @Override
    public synchronized List<Double> score(String query, List<String> documents) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Cross-encoder scoring requires a non-blank query");
        }
        if (documents == null) {
            throw new IllegalArgumentException("Cross-encoder documents are required");
        }
        if (documents.isEmpty()) {
            return List.of();
        }
        if (batchScorer == null || inferenceExecutor == null) {
            throw new IllegalStateException("Cross-encoder provider is not initialized");
        }

        List<String> orderedDocuments = documents.stream()
                .map(document -> document == null ? "" : document)
                .toList();
        Future<List<Double>> future = inferenceExecutor.submit(
                () -> scoreInBatches(query, orderedDocuments));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            batchScorer.cancel();
            future.cancel(true);
            throw new IllegalStateException(
                    "Cross-encoder scoring timed out after " + timeout.toMillis() + " ms",
                    e);
        } catch (InterruptedException e) {
            batchScorer.cancel();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cross-encoder scoring was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cross-encoder scoring failed", e.getCause());
        }
    }

    private List<Double> scoreInBatches(String query, List<String> documents) {
        List<Double> scores = new ArrayList<>(documents.size());
        for (int offset = 0; offset < documents.size(); offset += batchSize) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Cross-encoder scoring was cancelled");
            }
            int end = Math.min(offset + batchSize, documents.size());
            List<String> batch = List.copyOf(documents.subList(offset, end));
            List<Double> batchScores = batchScorer.scoreBatch(query, batch);
            validateScores(batchScores, batch.size());
            scores.addAll(batchScores);
        }
        validateScores(scores, documents.size());
        return List.copyOf(scores);
    }

    private static void validateScores(List<Double> scores, int expectedCount) {
        if (scores == null || scores.size() != expectedCount) {
            throw new IllegalStateException(
                    "Cross-encoder provider returned %d scores for %d documents"
                            .formatted(scores == null ? 0 : scores.size(), expectedCount));
        }
        for (int index = 0; index < scores.size(); index++) {
            Double score = scores.get(index);
            if (score == null || !Double.isFinite(score)) {
                throw new IllegalStateException(
                        "Cross-encoder provider returned a non-finite score at index " + index);
            }
        }
    }

    @PreDestroy
    public void close() {
        if (batchScorer != null) {
            batchScorer.cancel();
        }
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdownNow();
            try {
                if (!inferenceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Cross-encoder inference executor did not terminate within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inferenceExecutor = null;
        }
        if (batchScorer != null) {
            try {
                batchScorer.close();
            } catch (Exception e) {
                log.warn("Failed to close Cross-Encoder provider cleanly: {}", e.getMessage());
            }
            batchScorer = null;
        }
    }

    static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Cross-Encoder artifact: " + path, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void validateRuntimeConfiguration() {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Cross-encoder batch size must be greater than zero");
        }
        if (maxSequenceLength <= 0 || maxSequenceLength > 512) {
            throw new IllegalArgumentException(
                    "Cross-encoder max sequence length must be between 1 and 512");
        }
        if (intraOpThreads <= 0) {
            throw new IllegalArgumentException("Cross-encoder intra-op threads must be greater than zero");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Cross-encoder timeout must be greater than zero");
        }
        if (batchScorer == null && modelPath == null) {
            throw new IllegalArgumentException("Cross-encoder batch scorer is required");
        }
    }

    private static ModelMetadata validateMetadata(ModelMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("Cross-encoder metadata is required");
        }
        requireNonBlank(metadata.model(), "Cross-encoder model name");
        requireNonBlank(metadata.version(), "Cross-encoder model version");
        validateExpectedSha256(metadata.sha256(), "model");
        return metadata;
    }

    private static void validateExpectedSha256(String sha256, String label) {
        if (sha256 == null || !sha256.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException(
                    "Cross-encoder " + label + " SHA-256 must contain 64 hex characters");
        }
    }

    private static void requireMatchingSha256(String expected, String actual, String label) {
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException(
                    "Cross-encoder %s SHA-256 mismatch: expected %s but found %s"
                            .formatted(label, expected, actual));
        }
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static Path requireRegularFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(label + " file is missing: " + path);
        }
        return path;
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("cross-encoder-onnx-", 0).factory());
    }

    interface BatchScorer extends AutoCloseable {

        List<Double> scoreBatch(String query, List<String> documents);

        default void cancel() {
        }

        @Override
        default void close() throws Exception {
        }
    }

    private static final class OnnxBatchScorer implements BatchScorer {

        private static final Set<String> REQUIRED_INPUTS = Set.of(
                "input_ids",
                "attention_mask",
                "token_type_ids");
        private static final String OUTPUT_NAME = "logits";

        private final int maxSequenceLength;
        private final OrtSession session;
        private final HuggingFaceTokenizer tokenizer;
        private final OrtEnvironment environment;
        private final AtomicReference<OrtSession.RunOptions> activeRunOptions = new AtomicReference<>();

        private OnnxBatchScorer(
                Path modelFile,
                Path tokenizerFile,
                int maxSequenceLength,
                int intraOpThreads) {
            this.maxSequenceLength = maxSequenceLength;
            try {
                this.tokenizer = HuggingFaceTokenizer.builder()
                        .optTokenizerPath(tokenizerFile)
                        .optAddSpecialTokens(true)
                        .optTruncation(true)
                        .optPadding(false)
                        .optMaxLength(maxSequenceLength)
                        .build();
                this.environment = OrtEnvironment.getEnvironment();
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    options.setInterOpNumThreads(1);
                    options.setIntraOpNumThreads(intraOpThreads);
                    options.setMemoryPatternOptimization(true);
                    options.setCPUArenaAllocator(true);
                    options.addCPU(true);
                    this.session = environment.createSession(modelFile.toString(), options);
                }
                validateSchema();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize ONNX Cross-Encoder provider", e);
            }
        }

        @Override
        public List<Double> scoreBatch(String query, List<String> documents) {
            List<Encoding> encodings = documents.stream()
                    .map(document -> tokenizer.encode(query, document))
                    .toList();
            int paddedLength = encodings.stream()
                    .mapToInt(encoding -> encoding.getIds().length)
                    .max()
                    .orElseThrow();
            if (paddedLength <= 0 || paddedLength > maxSequenceLength) {
                throw new IllegalStateException(
                        "Cross-encoder tokenizer produced invalid sequence length " + paddedLength);
            }

            long[][] inputIds = new long[encodings.size()][paddedLength];
            long[][] attentionMask = new long[encodings.size()][paddedLength];
            long[][] tokenTypeIds = new long[encodings.size()][paddedLength];
            for (int index = 0; index < encodings.size(); index++) {
                Encoding encoding = encodings.get(index);
                fillPadded(encoding.getIds(), inputIds[index]);
                fillPadded(encoding.getAttentionMask(), attentionMask[index]);
                fillPadded(encoding.getTypeIds(), tokenTypeIds[index]);
            }

            try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, inputIds);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(environment, attentionMask);
                 OnnxTensor typeIdsTensor = OnnxTensor.createTensor(environment, tokenTypeIds);
                 OrtSession.RunOptions runOptions = new OrtSession.RunOptions()) {
                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("input_ids", idsTensor);
                inputs.put("attention_mask", maskTensor);
                inputs.put("token_type_ids", typeIdsTensor);
                if (!activeRunOptions.compareAndSet(null, runOptions)) {
                    throw new IllegalStateException("Concurrent ONNX Cross-Encoder execution is not allowed");
                }
                try (OrtSession.Result result = session.run(inputs, Set.of(OUTPUT_NAME), runOptions)) {
                    OnnxValue output = result.get(OUTPUT_NAME)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Cross-encoder ONNX output is missing: " + OUTPUT_NAME));
                    return extractScores(output, documents.size());
                } finally {
                    activeRunOptions.compareAndSet(runOptions, null);
                }
            } catch (OrtException e) {
                throw new IllegalStateException("ONNX Cross-Encoder inference failed", e);
            }
        }

        @Override
        public void cancel() {
            OrtSession.RunOptions runOptions = activeRunOptions.get();
            if (runOptions == null) {
                return;
            }
            try {
                runOptions.setTerminate(true);
            } catch (OrtException e) {
                log.warn("Failed to terminate active ONNX Cross-Encoder inference: {}", e.getMessage());
            }
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (OrtException e) {
                log.warn("Failed to close ONNX Cross-Encoder session: {}", e.getMessage());
            }
            tokenizer.close();
        }

        private void validateSchema() {
            if (!session.getInputNames().containsAll(REQUIRED_INPUTS)) {
                throw new IllegalStateException(
                        "Cross-encoder ONNX inputs must contain " + REQUIRED_INPUTS
                                + " but found " + session.getInputNames());
            }
            if (!session.getOutputNames().contains(OUTPUT_NAME)) {
                throw new IllegalStateException(
                        "Cross-encoder ONNX outputs must contain " + OUTPUT_NAME
                                + " but found " + session.getOutputNames());
            }
        }

        private static List<Double> extractScores(OnnxValue output, int expectedBatchSize)
                throws OrtException {
            Object value = output.getValue();
            if (!(value instanceof float[][] logits)) {
                throw new IllegalStateException(
                        "Unexpected Cross-Encoder ONNX output type: " + value.getClass());
            }
            if (logits.length != expectedBatchSize) {
                throw new IllegalStateException(
                        "Cross-encoder ONNX output batch size %d does not match %d"
                                .formatted(logits.length, expectedBatchSize));
            }
            List<Double> scores = new ArrayList<>(logits.length);
            for (int index = 0; index < logits.length; index++) {
                if (logits[index] == null || logits[index].length != 1) {
                    throw new IllegalStateException(
                            "Cross-encoder ONNX logits at index " + index + " must contain one score");
                }
                scores.add((double) logits[index][0]);
            }
            return scores;
        }

        private static void fillPadded(long[] raw, long[] target) {
            if (raw == null || raw.length == 0) {
                return;
            }
            System.arraycopy(raw, 0, target, 0, Math.min(raw.length, target.length));
        }
    }
}
