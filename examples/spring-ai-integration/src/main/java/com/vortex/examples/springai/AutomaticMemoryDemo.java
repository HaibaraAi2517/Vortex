package com.vortex.examples.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** One real-model turn per JVM. Run again with the same namespace to test cross-session memory. */
public final class AutomaticMemoryDemo {
    public static void main(String[] args) throws Exception {
        String namespace = env("VORTEX_NAMESPACE", "personal");
        String message = env("AGENT_MESSAGE", "我现在住在哪里，喜欢喝什么咖啡？");
        String output = env("AGENT_REPORT", "");
        boolean explicit = !env("MODEL_BASE_URL", "").isBlank() || !env("MODEL_NAME", "").isBlank()
                || !env("MODEL_API_KEY", "").isBlank();
        String base = explicit ? required("MODEL_BASE_URL") : "https://api.deepseek.com/v1";
        String modelName = explicit ? required("MODEL_NAME") : "deepseek-chat";
        String key = explicit ? env("MODEL_API_KEY", "local") : required("DEEPSEEK_API_KEY");
        URI endpoint = URI.create(base);
        if (!java.util.Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Invalid model base URL");
        }
        var model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().baseUrl(base).apiKey(key).model(modelName)
                        .temperature(0.0).maxTokens(2048).maxRetries(0).timeout(java.time.Duration.ofSeconds(120)).build())
                .build();
        VortexMemoryClient memory = new VortexMemoryClient(URI.create(env("VORTEX_URL", "http://127.0.0.1:8080")),
                env("VORTEX_SECURITY_BEARER_TOKEN", ""));
        VortexMemoryAdvisor advisor = new VortexMemoryAdvisor(memory, namespace, new VortexFactLearner(memory, model));
        ChatClient chat = ChatClient.builder(model).defaultAdvisors(advisor)
                .defaultSystem("你是一个有长期记忆的助手。简洁、准确地回答用户。").build();
        var response = chat.prompt().user(message).call().chatClientResponse();
        String answer = response.chatResponse().getResult().getOutput().getText();
        var report = (VortexFactLearner.Report) response.context().get(VortexMemoryAdvisor.CONTEXT_MEMORY_REPORT);
        if (report == null) throw new IllegalStateException("Memory report missing from advisor response");
        var result = new LinkedHashMap<String, Object>();
        result.put("namespace", namespace);
        result.put("model", modelName);
        result.put("user_message", message);
        result.put("answer", answer);
        result.put("finish_reason", response.chatResponse().getResult().getMetadata().getFinishReason());
        result.put("recall_count", response.context().get(VortexMemoryAdvisor.CONTEXT_RECALL_COUNT));
        result.put("updates", report.updates());
        result.put("errors", report.errors());
        if (!output.isBlank()) {
            Path path = Path.of(output).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8);
        }
        System.out.println(answer);
        System.out.println("Memory commits: " + report.updates().size() + "; failures: " + report.errors().size());
        if (!report.memoryOk()) throw new IllegalStateException("Memory learning incomplete; inspect the report");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String key) {
        String value = env(key, "");
        if (value.isBlank()) throw new IllegalArgumentException("Set " + key + " for a real-model run");
        return value;
    }
}
