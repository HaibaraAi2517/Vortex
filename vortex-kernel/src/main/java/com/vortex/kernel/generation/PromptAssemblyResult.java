package com.vortex.kernel.generation;

import java.util.List;

public record PromptAssemblyResult(
        String systemPrompt,
        String userPrompt,
        int promptTokens,
        List<String> includedFragmentIds,
        List<String> omittedFragmentIds) {
}
