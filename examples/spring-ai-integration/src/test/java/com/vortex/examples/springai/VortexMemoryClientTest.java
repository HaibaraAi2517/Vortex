package com.vortex.examples.springai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VortexMemoryClientTest {

    @Test
    void shouldSendBearerTokenAndParseMemoryFragmentId() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/memory/store", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{\"count\":1}");
        });
        server.createContext("/api/v1/memory/recall", exchange -> respond(exchange,
                "{\"fragments\":[{\"fragment\":{\"id\":\"memory-1\",\"content\":\"durable fact\"},\"score\":0.9}]}"));
        server.start();

        try {
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            VortexMemoryClient client = new VortexMemoryClient(baseUri, "test-token");

            assertEquals(1, client.store("durable fact", "quickstart-test", List.of("test")));
            assertEquals("Bearer test-token", authorization.get());
            assertEquals("memory-1", client.recall("fact", "quickstart-test", 3, 128).getFirst().fragmentId());
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
