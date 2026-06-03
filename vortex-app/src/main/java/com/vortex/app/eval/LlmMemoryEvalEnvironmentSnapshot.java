package com.vortex.app.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMemoryEvalEnvironmentSnapshot {

    private String generationBaseUrl;
    private String generationModel;
    private List<String> actualGenerationModels;
    private Long generationTimeoutMs;
    private String bgeModelPath;
    private boolean bgeSafeHashMode;
    private Long l1MaxTokens;
    private String milvusCollection;
    private String minioKeyPrefix;
    private String datasetLocation;
    private String datasetVersion;
    private String baselineProfileId;
    private String strictVerifierProfileId;
    private String evalSystemPromptSha256;
    private Integer evalSystemPromptChars;
    private List<String> modes;
    private Integer evalParallelism;
    private String reportOutputDir;
    private String javaVersion;
    private String osName;
    private String osVersion;
    private String userDir;
    private String cliMainClass;
}
