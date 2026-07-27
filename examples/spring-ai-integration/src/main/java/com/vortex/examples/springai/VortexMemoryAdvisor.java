package com.vortex.examples.springai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VortexMemoryAdvisor implements BaseAdvisor {

    public static final String CONTEXT_NAMESPACE = "vortex.namespace";
    public static final String CONTEXT_TOP_K = "vortex.topK";
    public static final String CONTEXT_TOKEN_BUDGET = "vortex.tokenBudget";
    public static final String CONTEXT_RECALL_COUNT = "vortex.recall.count";

    private final VortexMemoryClient memoryClient;
    private final String defaultNamespace;
    private final int defaultTopK;
    private final int defaultTokenBudget;
    private final int order;

    public VortexMemoryAdvisor(VortexMemoryClient memoryClient, String defaultNamespace) {
        this(memoryClient, defaultNamespace, 5, 768, Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 20);
    }

    public VortexMemoryAdvisor(VortexMemoryClient memoryClient, String defaultNamespace,
            int defaultTopK, int defaultTokenBudget, int order) {
        this.memoryClient = memoryClient;
        this.defaultNamespace = defaultNamespace;
        this.defaultTopK = defaultTopK;
        this.defaultTokenBudget = defaultTokenBudget;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userQuery = lastUserText(request.prompt());
        if (userQuery.isBlank()) {
            return request;
        }

        Map<String, Object> context = new LinkedHashMap<>(request.context());
        String namespace = stringParam(context, CONTEXT_NAMESPACE, defaultNamespace);
        int topK = intParam(context, CONTEXT_TOP_K, defaultTopK);
        int tokenBudget = intParam(context, CONTEXT_TOKEN_BUDGET, defaultTokenBudget);

        List<VortexMemoryClient.RecallFragment> fragments = recall(userQuery, namespace, topK, tokenBudget);
        if (fragments.isEmpty()) {
            context.put(CONTEXT_RECALL_COUNT, 0);
            return request.mutate().context(context).build();
        }

        String memoryBlock = renderMemoryBlock(fragments);
        Prompt augmentedPrompt = request.prompt().augmentSystemMessage(memoryBlock);
        context.put(CONTEXT_RECALL_COUNT, fragments.size());
        return request.mutate()
                .prompt(augmentedPrompt)
                .context(context)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }

    private List<VortexMemoryClient.RecallFragment> recall(String userQuery, String namespace, int topK, int tokenBudget) {
        try {
            return memoryClient.recall(userQuery, namespace, topK, tokenBudget);
        } catch (IOException e) {
            throw new IllegalStateException("Vortex recall failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vortex recall was interrupted", e);
        }
    }

    private static String renderMemoryBlock(List<VortexMemoryClient.RecallFragment> fragments) {
        StringBuilder builder = new StringBuilder();
        builder.append("Relevant durable memory recalled from Vortex:\n");
        for (int i = 0; i < fragments.size(); i++) {
            builder.append(i + 1).append(". ").append(fragments.get(i).content()).append('\n');
        }
        builder.append("Use these facts only when they are relevant to the user request. Do not invent missing facts.");
        return builder.toString();
    }

    private static String lastUserText(Prompt prompt) {
        Message message = prompt.getLastUserOrToolResponseMessage();
        if (message != null && message.getText() != null) {
            return message.getText();
        }
        return prompt.getContents() == null ? "" : prompt.getContents();
    }

    private static String stringParam(Map<String, Object> context, String key, String fallback) {
        Object value = context.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static int intParam(Map<String, Object> context, String key, int fallback) {
        Object value = context.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}