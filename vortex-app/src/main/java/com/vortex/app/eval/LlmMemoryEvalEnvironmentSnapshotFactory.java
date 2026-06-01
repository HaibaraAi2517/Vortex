package com.vortex.app.eval;

import com.vortex.kernel.generation.GenerationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class LlmMemoryEvalEnvironmentSnapshotFactory {

    private static final String CLI_MAIN_CLASS = "com.vortex.app.eval.LlmMemoryEvalCliApplication";

    private final LlmMemoryEvalProperties evalProperties;
    private final GenerationProperties generationProperties;

    @Value("${vortex.kernel.embedding.bge.model-path:}")
    private String bgeModelPath;

    @Value("${vortex.kernel.embedding.bge.safe-hash-mode:false}")
    private boolean bgeSafeHashMode;

    @Value("${vortex.storage.l1.max-tokens:8192}")
    private long l1MaxTokens;

    @Value("${vortex.storage.l2.milvus.collection:vortex_memory}")
    private String milvusCollection;

    @Value("${vortex.storage.l3.minio.key-prefix:}")
    private String minioKeyPrefix;

    @Value("${user.dir}")
    private String userDir;

    public LlmMemoryEvalEnvironmentSnapshot snapshot() {
        return LlmMemoryEvalEnvironmentSnapshot.builder()
                .generationBaseUrl(generationProperties.getBaseUrl())
                .generationModel(generationProperties.getModel())
                .generationTimeoutMs(durationToMillis(generationProperties.getTimeout()))
                .bgeModelPath(bgeModelPath)
                .bgeSafeHashMode(bgeSafeHashMode)
                .l1MaxTokens(l1MaxTokens)
                .milvusCollection(milvusCollection)
                .minioKeyPrefix(minioKeyPrefix)
                .datasetLocation(evalProperties.getDatasetLocation())
                .evalSystemPromptSha256(sha256Hex(evalProperties.getSystemPrompt()))
                .evalSystemPromptChars(evalProperties.getSystemPrompt() == null ? 0 : evalProperties.getSystemPrompt().length())
                .modes(configuredModes())
                .reportOutputDir(evalProperties.getReportOutputDir())
                .javaVersion(System.getProperty("java.version"))
                .osName(System.getProperty("os.name"))
                .osVersion(System.getProperty("os.version"))
                .userDir(userDir)
                .cliMainClass(CLI_MAIN_CLASS)
                .build();
    }

    private List<String> configuredModes() {
        return evalProperties.getModes() == null
                ? List.of()
                : evalProperties.getModes().stream().map(LlmMemoryEvalMode::reportName).toList();
    }

    private Long durationToMillis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    private String sha256Hex(String value) {
        String normalized = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable for eval environment snapshot", e);
        }
    }
}
