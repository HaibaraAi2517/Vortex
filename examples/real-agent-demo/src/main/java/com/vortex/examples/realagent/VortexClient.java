package com.vortex.examples.realagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class VortexClient {

    private static final String EXECUTION_ID_HEADER = "X-Execution-Id";

    private final URI baseUri;
    private final String bearerToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    VortexClient(URI baseUri, String bearerToken) {
        this(baseUri, bearerToken, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    VortexClient(URI baseUri, String bearerToken, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUri = baseUri;
        this.bearerToken = bearerToken;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    String health() throws IOException, InterruptedException {
        return getJson("/actuator/health", null).path("status").asText("UNKNOWN");
    }

    int store(String content, String namespace, List<String> tags) throws IOException, InterruptedException {
        JsonNode response = postJson("/api/v1/memory/store", Map.of(
                "content", content,
                "namespace", namespace,
                "tags", tags), null);
        return response.path("count").asInt(0);
    }

    List<RecallFragment> recall(String query, String namespace, int topK, int tokenBudget)
            throws IOException, InterruptedException {
        JsonNode response = postJson("/api/v1/memory/recall", Map.of(
                "query", query,
                "namespace", namespace,
                "topK", topK,
                "tokenBudget", tokenBudget), null);
        List<RecallFragment> fragments = new ArrayList<>();
        for (JsonNode item : response.path("fragments")) {
            JsonNode fragment = item.path("fragment");
            String content = firstText(fragment, "content");
            if (content == null) {
                content = firstText(item, "content");
            }
            if (content == null || content.isBlank()) {
                continue;
            }
            String fragmentId = firstText(fragment, "id", "fragmentId");
            if (fragmentId == null) {
                fragmentId = firstText(item, "id", "fragmentId");
            }
            fragments.add(new RecallFragment(fragmentId, content, item.path("score").asDouble(0.0)));
        }
        return List.copyOf(fragments);
    }

    TaskView createTask(String description, String namespace, String executionId)
            throws IOException, InterruptedException {
        JsonNode response = postJson("/api/v1/tasks", Map.of(
                "description", description,
                "namespace", namespace), executionId);
        return taskView(response);
    }

    NodeView appendNode(String taskId, String type, String content, String executionId)
            throws IOException, InterruptedException {
        JsonNode response = postJson("/api/v1/tasks/" + taskId + "/nodes", Map.of(
                "type", type,
                "content", content), executionId);
        return new NodeView(response.path("nodeId").asText(), response.path("type").asText(),
                response.path("content").asText());
    }

    NodeView completeNode(String taskId, String nodeId, String result, String executionId)
            throws IOException, InterruptedException {
        JsonNode response = postJson("/api/v1/tasks/" + taskId + "/nodes/complete", Map.of(
                "nodeId", nodeId,
                "result", result), executionId);
        return new NodeView(response.path("nodeId").asText(), response.path("type").asText(),
                response.path("content").asText());
    }

    String checkpoint(String taskId, String executionId) throws IOException, InterruptedException {
        return postJson("/api/v1/tasks/" + taskId + "/checkpoint", Map.of(), executionId)
                .path("checkpointId").asText();
    }

    TaskView recover(String taskId, String checkpointId, String executionId)
            throws IOException, InterruptedException {
        Map<String, Object> body = checkpointId == null || checkpointId.isBlank()
                ? Map.of()
                : Map.of("checkpointId", checkpointId);
        return taskView(postJson("/api/v1/tasks/" + taskId + "/recover", body, executionId));
    }

    TaskView getTask(String taskId) throws IOException, InterruptedException {
        return taskView(getJson("/api/v1/tasks/" + taskId, null));
    }

    void completeTask(String taskId, String executionId) throws IOException, InterruptedException {
        postJson("/api/v1/tasks/" + taskId + "/complete", Map.of(), executionId);
    }

    private JsonNode getJson(String path, String executionId) throws IOException, InterruptedException {
        HttpRequest.Builder builder = requestBuilder(path, executionId).GET();
        return send(builder.build());
    }

    private JsonNode postJson(String path, Map<String, Object> body, String executionId)
            throws IOException, InterruptedException {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = requestBuilder(path, executionId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return send(request);
    }

    private HttpRequest.Builder requestBuilder(String path, String executionId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(120));
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (executionId != null && !executionId.isBlank()) {
            builder.header(EXECUTION_ID_HEADER, executionId);
        }
        return builder;
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Vortex API returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body() == null || response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
    }

    private static TaskView taskView(JsonNode response) {
        Map<String, String> context = new LinkedHashMap<>();
        response.path("context").fields().forEachRemaining(entry -> context.put(entry.getKey(), entry.getValue().asText()));
        return new TaskView(
                response.path("taskId").asText(),
                response.path("status").asText(),
                response.path("namespace").asText(),
                response.path("nodeCount").asInt(0),
                response.path("latestCheckpointId").asText(null),
                Map.copyOf(context));
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    record RecallFragment(String fragmentId, String content, double score) {
    }

    record TaskView(
            String taskId,
            String status,
            String namespace,
            int nodeCount,
            String latestCheckpointId,
            Map<String, String> context) {
    }

    record NodeView(String nodeId, String type, String content) {
    }
}
