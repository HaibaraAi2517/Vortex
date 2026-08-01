package com.vortex.integration.langchain4j;

import com.vortex.common.dto.GenerationRequest;
import com.vortex.common.dto.GenerationResult;
import com.vortex.common.exception.GenerationException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jGenerationAdapterTest {

    @Test
    void mapsVortexRequestAndLangChain4jResponse() {
        CapturingChatModel model = new CapturingChatModel();
        LangChain4jGenerationAdapter adapter = new LangChain4jGenerationAdapter(model);

        GenerationResult result = adapter.generate(GenerationRequest.builder()
                .systemPrompt("Use durable memory.")
                .userPrompt("Resume the task.")
                .model("requested-model")
                .temperature(0.2)
                .maxTokens(128)
                .build());

        ChatRequest request = model.lastRequest;
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0)).isEqualTo(SystemMessage.from("Use durable memory."));
        assertThat(request.messages().get(1)).isEqualTo(UserMessage.from("Resume the task."));
        assertThat(request.modelName()).isEqualTo("requested-model");
        assertThat(request.temperature()).isEqualTo(0.2);
        assertThat(request.maxOutputTokens()).isEqualTo(128);
        assertThat(result.getContent()).isEqualTo("resumed");
        assertThat(result.getModel()).isEqualTo("response-model");
        assertThat(result.getRequestId()).isEqualTo("request-1");
        assertThat(result.getFinishReason()).isEqualTo("STOP");
        assertThat(result.getPromptTokens()).isEqualTo(7);
        assertThat(result.getCompletionTokens()).isEqualTo(3);
        assertThat(result.getTotalTokens()).isEqualTo(10);
        assertThat(result.getResponseMetadata()).containsEntry("adapter", "langchain4j");
    }

    @Test
    void rejectsBlankPromptBeforeCallingModel() {
        CapturingChatModel model = new CapturingChatModel();
        LangChain4jGenerationAdapter adapter = new LangChain4jGenerationAdapter(model);

        assertThatThrownBy(() -> adapter.generate(GenerationRequest.builder().userPrompt(" ").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank user prompt");
        assertThat(model.lastRequest).isNull();
    }

    @Test
    void wrapsProviderFailureWithVortexGenerationException() {
        ChatModel failingModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("provider unavailable");
            }
        };
        LangChain4jGenerationAdapter adapter = new LangChain4jGenerationAdapter(failingModel);

        assertThatThrownBy(() -> adapter.generate(
                GenerationRequest.builder().userPrompt("resume").build()))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("provider unavailable")
                .extracting("errorType")
                .isEqualTo(LangChain4jGenerationAdapter.ERROR_TYPE);
    }

    private static final class CapturingChatModel implements ChatModel {
        private ChatRequest lastRequest;

        @Override
        public ChatResponse chat(ChatRequest request) {
            lastRequest = request;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("resumed"))
                    .id("request-1")
                    .modelName("response-model")
                    .finishReason(FinishReason.STOP)
                    .tokenUsage(new TokenUsage(7, 3, 10))
                    .build();
        }
    }
}
