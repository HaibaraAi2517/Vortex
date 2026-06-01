package com.vortex.kernel.generation;

import com.vortex.common.dto.RecallResult;

public record PromptAssemblyRequest(
        String systemPrompt,
        String question,
        RecallResult recallResult,
        String taskContext,
        int maxPromptTokens) {
}
