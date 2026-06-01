package com.vortex.kernel.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vortex.common.dto.GenerationRequest;
import com.vortex.common.dto.GenerationResult;
import com.vortex.common.exception.GenerationException;
import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleGenerationServiceTest {

    private final ObjectMapper objectMapper = JsonMapperFactory.create();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void generateShouldCaptureDetailedLatencyBreakdown() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sleep(25L);
            writeJson(exchange, 200, """
                    {
                      "id": "resp-123",
                      "model": "gpt-5.2",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "answer from model"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 17,
                        "completion_tokens": 4,
                        "total_tokens": 21
                      }
                    }
                    """);
        });
        server.start();

        OpenAiCompatibleGenerationService service = new OpenAiCompatibleGenerationService(
                generationProperties("http://localhost:" + server.getAddress().getPort() + "/v1/"),
                objectMapper);

        GenerationResult result = service.generate(GenerationRequest.builder()
                .systemPrompt("system")
                .userPrompt("user")
                .build());

        assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(requestBody.get()).contains("\"model\":\"gpt-5.2\"");
        assertThat(requestBody.get()).contains("\"role\":\"system\"");
        assertThat(requestBody.get()).contains("\"role\":\"user\"");
        assertThat(result.getContent()).isEqualTo("answer from model");
        assertThat(result.getModel()).isEqualTo("gpt-5.2");
        assertThat(result.getRequestId()).isEqualTo("resp-123");
        assertThat(result.getFinishReason()).isEqualTo("stop");
        assertThat(result.getLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown()).isNotNull();
        assertThat(result.getLatencyBreakdown().getRequestBuildLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown().getHttpRoundTripLatencyMs()).isGreaterThanOrEqualTo(20L);
        assertThat(result.getLatencyBreakdown().getResponseParseLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown().getRetryBackoffLatencyMs()).isZero();
        assertThat(result.getLatencyBreakdown().getTotalLatencyMs()).isEqualTo(result.getLatencyMs());
        assertThat(result.getLatencyBreakdown().getRequestBuildLatencyNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown().getRequestSerializationLatencyNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown().getHttpRequestBuildLatencyNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown().getHttpRoundTripLatencyNanos()).isGreaterThanOrEqualTo(20_000_000L);
        assertThat(result.getLatencyBreakdown().getResponseParseLatencyNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown().getResponseDecodeLatencyNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown().getResponseJsonParseLatencyNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.getLatencyBreakdown().getRetryBackoffLatencyNanos()).isZero();
        assertThat(result.getLatencyBreakdown().getAttemptCount()).isEqualTo(1);
        assertThat(result.getLatencyBreakdown().getHttpStatusCode()).isEqualTo(200);
        assertThat(result.getLatencyBreakdown().getRequestBytes()).isPositive();
        assertThat(result.getLatencyBreakdown().getResponseBytes()).isPositive();
        assertThat(result.getLatencyBreakdown().getResponseParseLatencyNanos())
                .isGreaterThanOrEqualTo(result.getLatencyBreakdown().getResponseJsonParseLatencyNanos());
        assertThat(result.getLatencyMs()).isGreaterThanOrEqualTo(result.getLatencyBreakdown().totalLatencyMs());
        assertThat(result.getLatencyBreakdown().totalLatencyNanos()).isGreaterThanOrEqualTo(
                result.getLatencyBreakdown().getRequestBuildLatencyNanos()
                        + result.getLatencyBreakdown().getHttpRoundTripLatencyNanos()
                        + result.getLatencyBreakdown().getResponseParseLatencyNanos());
        assertThat(result.getResponseMetadata())
                .containsEntry("httpStatus", "200")
                .containsEntry("responseId", "resp-123")
                .containsEntry("finishReason", "stop");
    }

    @Test
    void generateShouldThrowOnNonSuccessStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange ->
                writeJson(exchange, 502, "{\"error\":\"gateway unavailable\"}"));
        server.start();

        OpenAiCompatibleGenerationService service = new OpenAiCompatibleGenerationService(
                generationProperties("http://localhost:" + server.getAddress().getPort() + "/v1"),
                objectMapper);

        assertThatThrownBy(() -> service.generate(GenerationRequest.builder()
                .userPrompt("user")
                .build()))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("status=502");
    }

    private GenerationProperties generationProperties(String baseUrl) {
        GenerationProperties properties = new GenerationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("test-key");
        properties.setModel("gpt-5.2");
        properties.setTemperature(0.0d);
        properties.setMaxTokens(128);
        properties.setTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        } finally {
            exchange.close();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating generation latency", e);
        }
    }
}
