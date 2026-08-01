package com.vortex.integration.langchain4j;

import com.vortex.common.dto.GenerationLatencyBreakdown;
import com.vortex.common.dto.GenerationRequest;
import com.vortex.common.dto.GenerationResult;
import com.vortex.common.exception.GenerationException;
import com.vortex.kernel.generation.GenerationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Adapts a LangChain4j {@link ChatModel} to Vortex's generation contract. */
public final class LangChain4jGenerationAdapter implements GenerationService {

    public static final String ERROR_TYPE = "langchain4j_generation_error";

    private final ChatModel chatModel;

    public LangChain4jGenerationAdapter(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        if (request == null || isBlank(request.getUserPrompt())) {
            throw new IllegalArgumentException("Generation request requires a non-blank user prompt");
        }

        long startedAt = System.nanoTime();
        try {
            ChatResponse response = chatModel.chat(toChatRequest(request));
            GenerationLatencyBreakdown latency = latencySince(startedAt);
            if (response == null || response.aiMessage() == null || isBlank(response.aiMessage().text())) {
                throw new GenerationException(
                        "LangChain4j ChatModel returned an empty response",
                        null,
                        ERROR_TYPE,
                        false,
                        latency);
            }

            TokenUsage usage = response.tokenUsage();
            return GenerationResult.builder()
                    .content(response.aiMessage().text())
                    .model(firstNonBlank(response.modelName(), request.getModel()))
                    .requestId(response.id())
                    .finishReason(response.finishReason() == null ? null : response.finishReason().name())
                    .promptTokens(usage == null ? null : usage.inputTokenCount())
                    .completionTokens(usage == null ? null : usage.outputTokenCount())
                    .totalTokens(usage == null ? null : usage.totalTokenCount())
                    .latencyMs(latency.getTotalLatencyMs())
                    .latencyBreakdown(latency)
                    .responseMetadata(Map.of("adapter", "langchain4j"))
                    .build();
        } catch (GenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GenerationException(
                    "LangChain4j ChatModel invocation failed: " + safeMessage(e),
                    e,
                    ERROR_TYPE,
                    false,
                    latencySince(startedAt));
        }
    }

    private ChatRequest toChatRequest(GenerationRequest request) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (!isBlank(request.getSystemPrompt())) {
            messages.add(SystemMessage.from(request.getSystemPrompt().trim()));
        }
        messages.add(UserMessage.from(request.getUserPrompt().trim()));

        ChatRequest.Builder builder = ChatRequest.builder().messages(List.copyOf(messages));
        if (!isBlank(request.getModel())) {
            builder.modelName(request.getModel().trim());
        }
        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            builder.maxOutputTokens(request.getMaxTokens());
        }
        return builder.build();
    }

    private GenerationLatencyBreakdown latencySince(long startedAt) {
        long elapsedNanos = System.nanoTime() - startedAt;
        return GenerationLatencyBreakdown.builder()
                .totalLatencyNanos(elapsedNanos)
                .totalLatencyMs(TimeUnit.NANOSECONDS.toMillis(elapsedNanos))
                .attemptCount(1)
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return isBlank(primary) ? fallback : primary;
    }

    private static String safeMessage(RuntimeException error) {
        return isBlank(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage();
    }
}
