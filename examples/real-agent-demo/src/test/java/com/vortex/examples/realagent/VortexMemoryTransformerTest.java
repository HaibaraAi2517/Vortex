package com.vortex.examples.realagent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VortexMemoryTransformerTest {

    @Test
    void injectsRecalledFactsAsSystemMemory() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/memory/recall", exchange -> respond(exchange,
                "{\"fragments\":[{\"fragment\":{\"id\":\"memory-1\","
                        + "\"content\":\"deployment codename is Aurora Ledger; approval owner is Lin-7\"},"
                        + "\"score\":0.95}]}"));
        server.start();

        try {
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            VortexMemoryTransformer transformer = new VortexMemoryTransformer(
                    new VortexClient(baseUri, "test-token"), "quickstart-test", 5, 512);
            ChatRequest request = ChatRequest.builder()
                    .messages(UserMessage.from("What is the private deployment codename?"))
                    .build();

            ChatRequest transformed = transformer.apply(request);

            String injected = transformed.messages().stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .map(SystemMessage::text)
                    .findFirst()
                    .orElseThrow();
            assertTrue(injected.contains("Aurora Ledger"));
            assertTrue(injected.contains("Do not invent missing facts"));
            assertEquals("memory-1", transformer.lastFragments().getFirst().fragmentId());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
