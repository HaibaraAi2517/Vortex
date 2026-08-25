package com.vortex.examples.realagent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VortexClientTest {

    @Test
    void exercisesMemoryAndRecoveryApiWithAuthenticationAndExecutionIds() throws Exception {
        List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        List<String> executionIds = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String executionId = exchange.getRequestHeaders().getFirst("X-Execution-Id");
            if (executionId != null) {
                executionIds.add(executionId);
            }
            route(exchange);
        });
        server.start();

        try {
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            VortexClient client = new VortexClient(baseUri, "test-token");

            assertEquals("UP", client.health());
            assertEquals(1, client.store("private fact", "quickstart-test", List.of("test")));
            assertEquals("memory-1", client.recall("fact", "quickstart-test", 3, 128)
                    .getFirst().fragmentId());
            assertEquals("task-1", client.createTask("demo", "quickstart-test", "exec-create").taskId());
            assertEquals("node-1", client.appendNode("task-1", "ACTION", "done", "exec-node").nodeId());
            assertEquals("node-1", client.completeNode(
                    "task-1", "node-1", "complete", "exec-node-complete").nodeId());
            assertEquals("checkpoint-1", client.checkpoint("task-1", "exec-checkpoint"));
            assertEquals(1, client.recover("task-1", "checkpoint-1", "exec-recover").nodeCount());
            assertEquals("checkpoint-1", client.getTask("task-1").latestCheckpointId());
            client.completeTask("task-1", "exec-complete");

            assertTrue(authorizationHeaders.stream().allMatch("Bearer test-token"::equals));
            assertEquals(List.of("exec-create", "exec-node", "exec-node-complete", "exec-checkpoint",
                            "exec-recover", "exec-complete"),
                    new ArrayList<>(executionIds));
        } finally {
            server.stop(0);
        }
    }

    private static void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = switch (path) {
            case "/actuator/health" -> "{\"status\":\"UP\"}";
            case "/api/v1/memory/store" -> "{\"count\":1}";
            case "/api/v1/memory/recall" -> "{\"fragments\":[{\"fragment\":{\"id\":\"memory-1\",\"content\":\"private fact\"},\"score\":0.9}]}";
            case "/api/v1/tasks" -> task("RUNNING", 0, null);
            case "/api/v1/tasks/task-1/nodes" -> "{\"nodeId\":\"node-1\",\"type\":\"ACTION\",\"content\":\"done\"}";
            case "/api/v1/tasks/task-1/nodes/complete" -> "{\"nodeId\":\"node-1\",\"type\":\"ACTION\",\"content\":\"done\",\"status\":\"COMPLETED\"}";
            case "/api/v1/tasks/task-1/checkpoint" -> "{\"taskId\":\"task-1\",\"checkpointId\":\"checkpoint-1\"}";
            case "/api/v1/tasks/task-1/recover", "/api/v1/tasks/task-1" -> task("RUNNING", 1, "checkpoint-1");
            case "/api/v1/tasks/task-1/complete" -> "{\"taskId\":\"task-1\",\"status\":\"COMPLETED\"}";
            default -> throw new IOException("Unexpected test path: " + path);
        };
        respond(exchange, body);
    }

    private static String task(String status, int nodeCount, String checkpointId) {
        String checkpoint = checkpointId == null ? "null" : "\"" + checkpointId + "\"";
        return "{\"taskId\":\"task-1\",\"status\":\"" + status
                + "\",\"namespace\":\"quickstart-test\",\"nodeCount\":" + nodeCount
                + ",\"latestCheckpointId\":" + checkpoint + ",\"context\":{}}";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
