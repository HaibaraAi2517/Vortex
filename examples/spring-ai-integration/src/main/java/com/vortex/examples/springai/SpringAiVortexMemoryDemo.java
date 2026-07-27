package com.vortex.examples.springai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SpringAiVortexMemoryDemo {

    private SpringAiVortexMemoryDemo() {
    }

    public static void main(String[] args) throws Exception {
        URI vortexBaseUrl = URI.create(env("VORTEX_BASE_URL", "http://localhost:8080"));
        String namespace = env("VORTEX_NAMESPACE", "spring-ai-demo-" + Instant.now().toEpochMilli());

        VortexMemoryClient client = new VortexMemoryClient(vortexBaseUrl);
        String memory = "Spring AI integration demo facts: launch codename is Aurora Ledger; "
                + "the integration target is Spring AI ChatClient advisor; no external LLM API key is required.";
        int stored = client.store(memory, namespace, List.of("spring-ai", "demo"));

        VortexMemoryAdvisor advisor = new VortexMemoryAdvisor(client, namespace, 5, 768, 0);
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("What is the launch codename and which Spring AI integration target should I use?"))
                .context(Map.of(VortexMemoryAdvisor.CONTEXT_NAMESPACE, namespace))
                .build();

        CapturingCallAdvisorChain chain = new CapturingCallAdvisorChain();
        advisor.adviseCall(request, chain);

        ChatClientRequest augmented = chain.lastRequest();
        String systemMemory = augmented.prompt().getSystemMessage().getText();
        System.out.println("Vortex base URL: " + vortexBaseUrl);
        System.out.println("Namespace: " + namespace);
        System.out.println("Stored fragments: " + stored);
        System.out.println("Advisor recall count: " + augmented.context().get(VortexMemoryAdvisor.CONTEXT_RECALL_COUNT));
        System.out.println();
        System.out.println("Injected Spring AI system memory:");
        System.out.println(systemMemory);

        if (!systemMemory.contains("Aurora Ledger") || !systemMemory.contains("Spring AI ChatClient advisor")) {
            throw new IllegalStateException("Advisor did not inject the expected Vortex memory.");
        }
        System.out.println();
        System.out.println("Demo complete. No external LLM API key was used.");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class CapturingCallAdvisorChain implements CallAdvisorChain {
        private ChatClientRequest lastRequest;

        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            this.lastRequest = request;
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("mock response")))))
                    .context(request.context())
                    .build();
        }

        @Override
        public List<CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(CallAdvisor advisor) {
            return this;
        }

        ChatClientRequest lastRequest() {
            if (lastRequest == null) {
                throw new IllegalStateException("Advisor chain was not invoked.");
            }
            return lastRequest;
        }
    }
}