package com.vortex.examples.realagent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

final class VortexMemoryTransformer implements UnaryOperator<ChatRequest> {

    private final VortexClient client;
    private final String namespace;
    private final int topK;
    private final int tokenBudget;
    private volatile List<VortexClient.RecallFragment> lastFragments = List.of();

    VortexMemoryTransformer(VortexClient client, String namespace, int topK, int tokenBudget) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.topK = topK;
        this.tokenBudget = tokenBudget;
    }

    @Override
    public ChatRequest apply(ChatRequest request) {
        String query = lastUserText(request.messages());
        if (query.isBlank()) {
            lastFragments = List.of();
            return request;
        }

        List<VortexClient.RecallFragment> fragments = recallWithRetry(query);
        lastFragments = fragments;
        if (fragments.isEmpty()) {
            return request;
        }

        List<ChatMessage> messages = new ArrayList<>(request.messages().size() + 1);
        boolean inserted = false;
        for (ChatMessage message : request.messages()) {
            if (!inserted && !(message instanceof SystemMessage)) {
                messages.add(SystemMessage.from(render(fragments)));
                inserted = true;
            }
            messages.add(message);
        }
        if (!inserted) {
            messages.add(SystemMessage.from(render(fragments)));
        }
        return request.toBuilder().messages(List.copyOf(messages)).build();
    }

    List<VortexClient.RecallFragment> lastFragments() {
        return lastFragments;
    }

    private List<VortexClient.RecallFragment> recallWithRetry(String query) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                List<VortexClient.RecallFragment> fragments = client.recall(query, namespace, topK, tokenBudget);
                if (!fragments.isEmpty()) {
                    return fragments;
                }
            } catch (IOException e) {
                lastFailure = new IllegalStateException("Vortex recall failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Vortex recall was interrupted", e);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Vortex recall retry was interrupted", e);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return List.of();
    }

    private static String render(List<VortexClient.RecallFragment> fragments) {
        StringBuilder builder = new StringBuilder("Relevant durable memory recalled from Vortex:\n");
        for (int index = 0; index < fragments.size(); index++) {
            builder.append(index + 1).append(". ").append(fragments.get(index).content()).append('\n');
        }
        builder.append("Use these facts only when relevant. Do not invent missing facts.");
        return builder.toString();
    }

    private static String lastUserText(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (message instanceof UserMessage userMessage && userMessage.hasSingleText()) {
                return userMessage.singleText();
            }
        }
        return "";
    }
}
