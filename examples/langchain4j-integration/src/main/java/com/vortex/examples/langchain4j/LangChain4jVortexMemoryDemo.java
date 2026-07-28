package com.vortex.examples.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;

import java.net.URI;
import java.time.Instant;
import java.util.List;

public final class LangChain4jVortexMemoryDemo {

    private LangChain4jVortexMemoryDemo() {
    }

    public static void main(String[] args) throws Exception {
        URI vortexBaseUrl = URI.create(env("VORTEX_BASE_URL", "http://localhost:8080"));
        String namespace = env("VORTEX_NAMESPACE", "langchain4j-demo-" + Instant.now().toEpochMilli());

        VortexMemoryClient client = new VortexMemoryClient(vortexBaseUrl);
        String memory = "LangChain4j integration demo facts: launch codename is Nimbus Ledger; "
                + "the integration target is AiServices chatRequestTransformer; "
                + "no external LLM API key is required.";
        int stored = client.store(memory, namespace, List.of("langchain4j", "demo"));

        VortexChatRequestTransformer transformer = new VortexChatRequestTransformer(client, namespace, 5, 768);
        CapturingChatModel chatModel = new CapturingChatModel();
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatRequestTransformer(transformer)
                .build();

        String answer = assistant.chat(
                "What is the launch codename and which LangChain4j integration hook should I use?");

        ChatRequest augmentedRequest = chatModel.lastRequest();
        String systemMemory = injectedSystemMemory(augmentedRequest);
        System.out.println("Vortex base URL: " + vortexBaseUrl);
        System.out.println("Namespace: " + namespace);
        System.out.println("Stored fragments: " + stored);
        System.out.println("Transformer recall count: " + transformer.lastRecallCount());
        System.out.println();
        System.out.println("Injected LangChain4j system memory:");
        System.out.println(systemMemory);
        System.out.println();
        System.out.println("Fake model answer:");
        System.out.println(answer);

        if (!systemMemory.contains("Nimbus Ledger") || !systemMemory.contains("AiServices chatRequestTransformer")) {
            throw new IllegalStateException("Transformer did not inject the expected Vortex memory.");
        }
        if (!answer.contains("Nimbus Ledger")) {
            throw new IllegalStateException("Fake model did not receive the expected Vortex memory.");
        }
        System.out.println();
        System.out.println("Demo complete. No external LLM API key was used.");
    }

    private static String injectedSystemMemory(ChatRequest request) {
        return request.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::text)
                .filter(text -> text.contains("Relevant durable memory recalled from Vortex"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No injected Vortex memory message was found."));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private interface Assistant {
        @dev.langchain4j.service.UserMessage("{{it}}")
        String chat(String userMessage);
    }

    private static final class CapturingChatModel implements ChatModel {
        private ChatRequest lastRequest;

        @Override
        public ChatResponse chat(ChatRequest request) {
            this.lastRequest = request;
            String memory = request.messages().stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .map(SystemMessage::text)
                    .filter(text -> text.contains("Nimbus Ledger"))
                    .findFirst()
                    .orElse("No Vortex memory was injected.");
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("mock answer using: " + singleLine(memory)))
                    .modelName("fake-langchain4j-chat-model")
                    .build();
        }

        ChatRequest lastRequest() {
            if (lastRequest == null) {
                throw new IllegalStateException("ChatModel was not invoked.");
            }
            return lastRequest;
        }

        private static String singleLine(String text) {
            return text.replace('\r', ' ').replace('\n', ' ');
        }
    }
}
