package com.vortex.examples.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class VortexMemoryClient {

    private final URI baseUri;
    private final String bearerToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VortexMemoryClient(URI baseUri) {
        this(baseUri, null);
    }

    public VortexMemoryClient(URI baseUri, String bearerToken) {
        if (baseUri == null || !("http".equals(baseUri.getScheme()) || "https".equals(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("An HTTP(S) base URI without credentials, query or fragment is required");
        }
        this.baseUri = baseUri;
        this.bearerToken = bearerToken;
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
        JsonNode response = postJson("/api/v1/memory/context", body);
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
            String fragmentId = textOrNull(fragment.path("id"));
            if (fragmentId == null) {
                fragmentId = textOrNull(fragment.path("fragmentId"));
            }
            if (fragmentId == null) {
                fragmentId = textOrNull(item.path("id"));
            }
            if (fragmentId == null) {
                fragmentId = textOrNull(item.path("fragmentId"));
            }
            fragments.add(new RecallFragment(fragmentId, content, item.path("score").asDouble(0.0)));
        }
        return fragments;
    }

    private JsonNode postJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
        return request("POST", path, body);
    }

    public JsonNode getFact(String namespace, String key) throws IOException, InterruptedException {
        try {
            return request("GET", "/api/v1/memory/facts?namespace=" + encode(namespace) + "&key=" + encode(key), null);
        } catch (ApiException e) {
            if (e.status() == 404) return null;
            throw e;
        }
    }

    public JsonNode remember(String namespace, String key, String content, long expectedRevision,
            List<String> tags, String expiresAt) throws IOException, InterruptedException {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("namespace", namespace);
        body.put("key", key);
        body.put("content", content);
        body.put("expectedRevision", expectedRevision);
        body.put("tags", tags);
        body.put("expiresAt", expiresAt);
        return request("PUT", "/api/v1/memory/facts", body);
    }

    public JsonNode retract(String namespace, String key, long expectedRevision) throws IOException, InterruptedException {
        return postJson("/api/v1/memory/facts/retract", Map.of("namespace", namespace, "key", key,
                "expectedRevision", expectedRevision));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode request(String method, String path, Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest request = requestBuilder
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            long revision = -1;
            try {
                revision = objectMapper.readTree(response.body()).path("currentRevision").asLong(-1);
            } catch (IOException ignored) { }
            throw new ApiException(response.statusCode(), revision);
        }
        return objectMapper.readTree(response.body());
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    public record RecallFragment(String fragmentId, String content, double score) {
    }

    public static final class ApiException extends IOException {
        private final int status;
        private final long currentRevision;
        ApiException(int status, long currentRevision) {
            super("Vortex API returned HTTP " + status);
            this.status = status;
            this.currentRevision = currentRevision;
        }
        public int status() { return status; }
        public long currentRevision() { return currentRevision; }
    }
}
