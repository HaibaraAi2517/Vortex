package com.vortex.app.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.vortex.kernel.embedding.BgeSmallEmbeddingService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-shot diagnostic CLI for isolating Windows native crash stages in BGE.
 */
public final class BgeNativeDiagnosticCli {

    private static final int MAX_SEQ_LEN = 512;
    private static final String DEFAULT_TEXT = "请记住：我的宠物叫奶糖，它是一只三岁的柯基。";

    private BgeNativeDiagnosticCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> parsedArgs = parseArgs(args);
        Path modelDir = resolveModelDir(parsedArgs.get("modelPath"));
        String stage = parsedArgs.getOrDefault("stage", "single-embed").trim();
        String text = parsedArgs.getOrDefault("text", DEFAULT_TEXT);

        System.out.println("[bge-diagnostic] modelDir=" + modelDir.toAbsolutePath());
        System.out.println("[bge-diagnostic] stage=" + stage);
        System.out.println("[bge-diagnostic] text=" + text);

        switch (stage) {
            case "tokenizer-only" -> runTokenizerOnly(modelDir, text);
            case "service-init" -> runServiceInit(modelDir);
            case "single-embed" -> runSingleEmbed(modelDir, text);
            case "manual-ort" -> runManualOrt(modelDir, text);
            default -> throw new IllegalArgumentException(
                    "Unsupported stage: " + stage + ". Expected one of tokenizer-only, service-init, single-embed, manual-ort");
        }
    }

    private static void runTokenizerOnly(Path modelDir, String text) throws Exception {
        try (HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(modelDir.resolve("tokenizer.json"))) {
            Encoding encoding = tokenizer.encode(BgeSmallEmbeddingService.buildRetrievalInput(text));
            System.out.println("[bge-diagnostic] tokenCount=" + encoding.getIds().length);
            System.out.println("[bge-diagnostic] firstIds=" + preview(encoding.getIds(), 12));
            System.out.println("[bge-diagnostic] attentionMaskPreview=" + preview(encoding.getAttentionMask(), 12));
        }
    }

    private static void runServiceInit(Path modelDir) throws Exception {
        BgeSmallEmbeddingService service = new BgeSmallEmbeddingService(modelDir.toString(), false);
        try {
            service.init();
            System.out.println("[bge-diagnostic] service initialized");
        } finally {
            service.close();
        }
    }

    private static void runSingleEmbed(Path modelDir, String text) throws Exception {
        BgeSmallEmbeddingService service = new BgeSmallEmbeddingService(modelDir.toString(), false);
        try {
            service.init();
            float[] embedding = service.embed(text);
            System.out.println("[bge-diagnostic] embeddingDim=" + embedding.length);
            System.out.println("[bge-diagnostic] firstValues=" + preview(embedding, 8));
        } finally {
            service.close();
        }
    }

    private static void runManualOrt(Path modelDir, String text) throws Exception {
        Path modelPath = resolveModelPath(modelDir);
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        try (HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
             OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
             OrtEnvironment environment = OrtEnvironment.getEnvironment();
             OrtSession session = environment.createSession(modelPath.toString(), configureCpuOnly(sessionOptions))) {

            System.out.println("[bge-diagnostic] ortProviders=" + OrtEnvironment.getAvailableProviders());
            System.out.println("[bge-diagnostic] ortInputs=" + session.getInputNames());
            System.out.println("[bge-diagnostic] ortOutputs=" + session.getOutputNames());
            dumpInputInfo(session.getInputInfo());

            Encoding encoding = tokenizer.encode(BgeSmallEmbeddingService.buildRetrievalInput(text));
            long[][] inputIds = new long[][]{pad(encoding.getIds())};
            long[][] attentionMask = new long[][]{pad(encoding.getAttentionMask())};
            long[][] tokenTypeIds = new long[][]{pad(encoding.getTypeIds())};

            try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, inputIds);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(environment, attentionMask);
                 OnnxTensor typeIdsTensor = OnnxTensor.createTensor(environment, tokenTypeIds)) {
                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("input_ids", idsTensor);
                inputs.put("attention_mask", maskTensor);
                inputs.put("token_type_ids", typeIdsTensor);

                try (OrtSession.Result result = session.run(inputs)) {
                    OnnxValue output = result.get(0);
                    Object value = output.getValue();
                    if (!(value instanceof float[][][] tensor) || tensor.length == 0 || tensor[0].length == 0) {
                        throw new IllegalStateException("Unexpected ORT output type: " + value.getClass());
                    }
                    float[] cls = tensor[0][0];
                    System.out.println("[bge-diagnostic] manualOrtEmbeddingDim=" + cls.length);
                    System.out.println("[bge-diagnostic] manualOrtFirstValues=" + preview(cls, 8));
                }
            }
        }
    }

    private static OrtSession.SessionOptions configureCpuOnly(OrtSession.SessionOptions options) throws OrtException {
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(1);
        options.setMemoryPatternOptimization(false);
        options.setCPUArenaAllocator(true);
        options.addCPU(true);
        return options;
    }

    private static void dumpInputInfo(Map<String, ai.onnxruntime.NodeInfo> inputInfo) {
        for (Map.Entry<String, ai.onnxruntime.NodeInfo> entry : inputInfo.entrySet()) {
            if (entry.getValue().getInfo() instanceof TensorInfo tensorInfo) {
                System.out.println("[bge-diagnostic] inputInfo " + entry.getKey()
                        + " shape=" + Arrays.toString(tensorInfo.getShape())
                        + " type=" + tensorInfo.type);
            } else {
                System.out.println("[bge-diagnostic] inputInfo " + entry.getKey() + " info=" + entry.getValue().getInfo());
            }
        }
    }

    private static long[] pad(long[] raw) {
        long[] padded = new long[MAX_SEQ_LEN];
        if (raw == null || raw.length == 0) {
            return padded;
        }
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, MAX_SEQ_LEN));
        return padded;
    }

    private static Path resolveModelDir(String argValue) {
        String configured = firstNonBlank(
                argValue,
                System.getenv("BGE_MODEL_PATH"),
                System.getProperty("bge.model.path"),
                "models/bge-small-zh");
        Path modelDir = Paths.get(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(modelDir)) {
            throw new IllegalArgumentException("BGE model directory not found: " + modelDir);
        }
        Path tokenizer = modelDir.resolve("tokenizer.json");
        if (!Files.exists(tokenizer)) {
            throw new IllegalArgumentException("tokenizer.json not found: " + tokenizer);
        }
        return modelDir;
    }

    private static Path resolveModelPath(Path modelDir) {
        List<Path> candidates = List.of(
                modelDir.resolve("model.onnx"),
                modelDir.resolve("onnx").resolve("model.onnx"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("model.onnx not found under: " + modelDir);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            int index = arg.indexOf('=');
            if (index < 0) {
                parsed.put(arg.substring(2), "true");
            } else {
                parsed.put(arg.substring(2, index), arg.substring(index + 1));
            }
        }
        return parsed;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String preview(long[] values, int limit) {
        int end = Math.min(values.length, limit);
        return Arrays.toString(Arrays.copyOf(values, end));
    }

    private static String preview(float[] values, int limit) {
        int end = Math.min(values.length, limit);
        return Arrays.toString(Arrays.copyOf(values, end));
    }
}
