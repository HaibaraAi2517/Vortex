package com.vortex.examples.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class VortexChatRequestTransformer implements UnaryOperator<ChatRequest> {

    private final VortexMemoryClient memoryClient;
    private final String namespace;
    private final int topK;
    private final int tokenBudget;
    private int lastRecallCount;

    public VortexChatRequestTransformer(VortexMemoryClient memoryClient, String namespace) {
        this(memoryClient, namespace, 5, 768);
    }

    public VortexChatRequestTransformer(VortexMemoryClient memoryClient, String namespace, int topK, int tokenBudget) {
        this.memoryClient = Objects.requireNonNull(memoryClient, "memoryClient");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.topK = topK;
        this.tokenBudget = tokenBudget;
    }

    @Override
    public ChatRequest apply(ChatRequest request) {
        String userQuery = lastUserText(request.messages());
        if (userQuery.isBlank()) {
            lastRecallCount = 0;
            return request;
        }

        List<VortexMemoryClient.RecallFragment> fragments = recall(userQuery);
        lastRecallCount = fragments.size();
        if (fragments.isEmpty()) {
            return request;
        }

        List<ChatMessage> augmentedMessages = injectMemoryMessage(request.messages(), renderMemoryBlock(fragments));
        return request.toBuilder()
                .messages(augmentedMessages)
                .build();
    }

    public int lastRecallCount() {
        return lastRecallCount;
    }

    private List<VortexMemoryClient.RecallFragment> recall(String userQuery) {
        try {
            return memoryClient.recall(userQuery, namespace, topK, tokenBudget);
        } catch (IOException e) {
            throw new IllegalStateException("Vortex recall failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vortex recall was interrupted", e);
        }
    }

    private static List<ChatMessage> injectMemoryMessage(List<ChatMessage> messages, String memoryBlock) {
        List<ChatMessage> augmented = new ArrayList<>(messages.size() + 1);
        boolean inserted = false;
        for (ChatMessage message : messages) {
            if (!inserted && !(message instanceof SystemMessage)) {
                augmented.add(SystemMessage.from(memoryBlock));
                inserted = true;
            }
            augmented.add(message);
        }
        if (!inserted) {
            augmented.add(SystemMessage.from(memoryBlock));
        }
        return List.copyOf(augmented);
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

    private static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof UserMessage userMessage && userMessage.hasSingleText()) {
                return userMessage.singleText();
            }
        }
        return "";
    }
}
