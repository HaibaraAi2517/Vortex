package com.vortex.kernel.generation;

import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.TokenCounter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PromptAssembler {

    static final String DEFAULT_SYSTEM_PROMPT = """
            You are a precise assistant.
            Use the provided memory fragments when they are relevant.
            If the memory is insufficient, say so explicitly instead of guessing.
            """;

    private final TokenCounter tokenCounter;
    private final int defaultMaxPromptTokens;

    public PromptAssembler(
            @Qualifier("bgeSmallEmbeddingService") TokenCounter tokenCounter,
            @Value("${vortex.kernel.generation.prompt.max-tokens:2048}") int defaultMaxPromptTokens) {
        this.tokenCounter = tokenCounter;
        this.defaultMaxPromptTokens = defaultMaxPromptTokens;
    }

    public PromptAssemblyResult assemble(PromptAssemblyRequest request) {
        if (request == null || isBlank(request.question())) {
            throw new IllegalArgumentException("Prompt assembly requires a non-blank question");
        }

        String systemPrompt = isBlank(request.systemPrompt())
                ? DEFAULT_SYSTEM_PROMPT
                : request.systemPrompt().trim();
        int maxPromptTokens = request.maxPromptTokens() > 0
                ? request.maxPromptTokens()
                : defaultMaxPromptTokens;

        RecallResult recallResult = request.recallResult();
        List<RecallResult.ScoredFragment> fragments = recallResult == null || recallResult.getFragments() == null
                ? List.of()
                : recallResult.getFragments();

        String taskContextSection = renderTaskContext(request.taskContext());
        String footer = renderFooter(request.question());
        String memoryHeader = "Memory fragments:\n";

        String baseWithoutTaskContext = memoryHeader + "(none)\n\n" + footer;
        String baseBody = taskContextSection + baseWithoutTaskContext;
        if (countPromptTokens(systemPrompt, baseBody) > maxPromptTokens) {
            baseBody = baseWithoutTaskContext;
            if (countPromptTokens(systemPrompt, baseBody) > maxPromptTokens) {
                throw new IllegalArgumentException(
                        "Prompt budget too small for the mandatory system/question sections");
            }
            taskContextSection = "";
        }

        List<String> includedFragmentIds = new ArrayList<>();
        List<String> omittedFragmentIds = new ArrayList<>();
        StringBuilder memorySection = new StringBuilder(memoryHeader);
        boolean includedAnyFragment = false;

        for (RecallResult.ScoredFragment scoredFragment : fragments) {
            MemoryFragment fragment = scoredFragment == null ? null : scoredFragment.getFragment();
            if (fragment == null || isBlank(fragment.getId()) || isBlank(fragment.getContent())) {
                continue;
            }
            String block = renderFragmentBlock(scoredFragment);
            String candidateMemory = memorySection.toString() + block;
            String candidateBody = taskContextSection + candidateMemory + "\n" + footer;
            if (countPromptTokens(systemPrompt, candidateBody) > maxPromptTokens) {
                omittedFragmentIds.add(fragment.getId());
                continue;
            }
            memorySection.append(block);
            includedAnyFragment = true;
            includedFragmentIds.add(fragment.getId());
        }

        if (!includedAnyFragment) {
            memorySection.append("(none)\n");
        }

        String userPrompt = taskContextSection + memorySection + "\n" + footer;
        return new PromptAssemblyResult(
                systemPrompt,
                userPrompt,
                countPromptTokens(systemPrompt, userPrompt),
                List.copyOf(includedFragmentIds),
                List.copyOf(omittedFragmentIds));
    }

    private String renderTaskContext(String taskContext) {
        if (isBlank(taskContext)) {
            return "";
        }
        return "Task context:\n" + taskContext.trim() + "\n\n";
    }

    private String renderFooter(String question) {
        return """
                Question:
                %s

                Answer requirements:
                - Ground the answer in the provided memory fragments when relevant.
                - If the answer is not supported by the provided memory, say that the memory is insufficient.
                - Do not fabricate fragment identifiers or hidden facts.
                """.formatted(question.trim());
    }

    private String renderFragmentBlock(RecallResult.ScoredFragment scoredFragment) {
        MemoryFragment fragment = scoredFragment.getFragment();
        String tier = scoredFragment.getTier() == null ? "UNKNOWN" : scoredFragment.getTier().trim();
        return """
                [fragmentId=%s][tier=%s][score=%.4f]
                %s

                """.formatted(
                fragment.getId(),
                tier,
                scoredFragment.getScore(),
                fragment.getContent().trim());
    }

    private int countPromptTokens(String systemPrompt, String userPrompt) {
        return Math.max(1, tokenCounter.countTokens(systemPrompt + "\n\n" + userPrompt));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
