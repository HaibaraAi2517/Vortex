package com.vortex.examples.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class VortexMemoryClient {

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VortexMemoryClient(URI baseUri) {
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public int store(String content, String namespace, List<String> tags) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "content", content,
                "namespace", namespace,
                "tags", tags);
        JsonNode response = postJson("/api/v1/memory/store", body);
        return response.path("count").asInt(0);
    }

    public List<RecallFragment> recall(String query, String namespace, int topK, int tokenBudget)
            throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "query", query,
                "namespace", namespace,
                "topK", topK,
                "tokenBudget", tokenBudget);
        JsonNode response = postJson("/api/v1/memory/recall", body);
        List<RecallFragment> fragments = new ArrayList<>();
        for (JsonNode item : response.path("fragments")) {
            JsonNode fragment = item.path("fragment");
            String content = textOrNull(fragment.path("content"));
            if (content == null) {
                content = textOrNull(item.path("content"));
            }
            if (content == null || content.isBlank()) {
                continue;
            }
            String fragmentId = textOrNull(fragment.path("fragmentId"));
            if (fragmentId == null) {
                fragmentId = textOrNull(item.path("fragmentId"));
            }
            fragments.add(new RecallFragment(fragmentId, content, item.path("score").asDouble(0.0)));
        }
        return fragments;
    }

    private JsonNode postJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Vortex API returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    public record RecallFragment(String fragmentId, String content, double score) {
    }
}
