package com.vortex.app.eval;

import com.vortex.common.dto.MemoryScenario;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vortex.eval")
public class LlmMemoryEvalProperties {

    private String datasetLocation = "classpath:llm-memory-eval-set.json";
    private List<LlmMemoryEvalMode> modes = new ArrayList<>(List.of(
            LlmMemoryEvalMode.BASELINE_NO_MEMORY,
            LlmMemoryEvalMode.VORTEX_MEMORY,
            LlmMemoryEvalMode.VORTEX_RECOVERED_MEMORY));
    private boolean runOnStartup = false;
    private boolean failOnStartupError = true;
    private boolean writeReport = true;
    private String reportOutputDir = "ops/eval-reports";
    private int recallTopK = 5;
    private int recallTokenBudget = 1024;
    private int maxPromptTokens = 2048;
    private boolean feedbackEnabled = true;
    private MemoryScenario learningScenario = MemoryScenario.CHAT;
    private Duration recoveryPollTimeout = Duration.ofSeconds(10);
    private Duration recoveryPollInterval = Duration.ofMillis(250);
    private int evictionFillerFragments = 6;
    private int evictionFillerTokens = 0;
    private double evictionFillerImportance = 0.95d;
    private String systemPrompt = """
            You are running a strict grounded memory QA evaluation.
            Use only the provided memory fragments.
            When the memory supports an answer, give the final concrete answer, not an intermediate description.
            Resolve references all the way to the leaf value, for example role -> person, alias -> canonical value, policy -> concrete setting, region indirection -> final region.
            Prefer present-state facts over historical, previous, old, or legacy facts unless the question explicitly asks about history.
            Do not treat a historical distractor as a conflict with a current fact unless both fragments explicitly describe the same current state.
            If multiple fragments are needed, combine them and answer the question directly in the first sentence.
            If the memory is insufficient, say so plainly.
            Do not fabricate hidden facts or fragment identifiers.
            """;
}
