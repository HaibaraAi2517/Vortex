package com.vortex.kernel.generation;

import com.vortex.common.dto.GenerationLatencyBreakdown;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.dto.GenerationRequest;
import com.vortex.common.dto.GenerationResult;
import com.vortex.common.exception.GenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(name = "vortex.kernel.generation.enabled", havingValue = "true")
public class OpenAiCompatibleGenerationService implements GenerationService {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final GenerationProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleGenerationService(GenerationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        validate(properties);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
        log.info("OpenAI-compatible generation service initialized baseUrl={} model={}",
                normalizeBaseUrl(properties.getBaseUrl()), properties.getModel());
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        if (request == null || isBlank(request.getUserPrompt())) {
            throw new IllegalArgumentException("Generation request requires a non-blank user prompt");
        }

        long startedAt = System.nanoTime();
        Duration timeout = request.getTimeoutMs() != null
                ? Duration.ofMillis(request.getTimeoutMs())
                : properties.getTimeout();
        double temperature = request.getTemperature() != null
                ? request.getTemperature()
                : properties.getTemperature();
        int maxTokens = request.getMaxTokens() != null
                ? request.getMaxTokens()
                : properties.getMaxTokens();
        String model = !isBlank(request.getModel()) ? request.getModel().trim() : properties.getModel();
        Map<String, String> metadata = request.getMetadata() == null ? Map.of() : Map.copyOf(request.getMetadata());
        GenerationLatencyBreakdown.GenerationLatencyBreakdownBuilder latencyBreakdownBuilder =
                GenerationLatencyBreakdown.builder();
        latencyBreakdownBuilder.attemptCount(1);

        try {
            long requestBuildStartedAt = System.nanoTime();
            ChatCompletionRequest payload = new ChatCompletionRequest(
                    model,
                    buildMessages(request),
                    temperature,
                    maxTokens);
            long requestSerializationStartedAt = System.nanoTime();
            String body = objectMapper.writeValueAsString(payload);
            byte[] requestBytes = body.getBytes(StandardCharsets.UTF_8);
            latencyBreakdownBuilder
                    .requestSerializationLatencyNanos(elapsedNanos(requestSerializationStartedAt))
                    .requestBytes(requestBytes.length);
            long httpRequestBuildStartedAt = System.nanoTime();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(properties.getBaseUrl()) + CHAT_COMPLETIONS_PATH))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes))
                    .build();
            long httpRequestBuildNanos = elapsedNanos(httpRequestBuildStartedAt);
            long requestBuildNanos = System.nanoTime() - requestBuildStartedAt;
            latencyBreakdownBuilder
                    .httpRequestBuildLatencyNanos(httpRequestBuildNanos)
                    .requestBuildLatencyNanos(requestBuildNanos)
                    .requestBuildLatencyMs(toMillis(requestBuildNanos));

            long httpStartedAt = System.nanoTime();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            long httpRoundTripNanos = elapsedNanos(httpStartedAt);
            latencyBreakdownBuilder
                    .httpRoundTripLatencyNanos(httpRoundTripNanos)
                    .httpRoundTripLatencyMs(toMillis(httpRoundTripNanos))
                    .httpStatusCode(response.statusCode())
                    .responseBytes(response.body() == null ? 0 : response.body().length);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(latencyBreakdownBuilder, startedAt);
                log.error("LLM generation failed status={} model={} latencyMs={} requestBuildMs={} httpMs={} parseMs={} retryBackoffMs={} metadata={} body={}",
                        response.statusCode(), model, latencyBreakdown.totalLatencyMs(),
                        latencyBreakdown.getRequestBuildLatencyMs(),
                        latencyBreakdown.getHttpRoundTripLatencyMs(),
                        latencyBreakdown.getResponseParseLatencyMs(),
                        latencyBreakdown.getRetryBackoffLatencyMs(),
                        metadata, abbreviate(decodeResponseBody(response.body())));
                throw new GenerationException("Generation API error status=" + response.statusCode());
            }

            long parseStartedAt = System.nanoTime();
            long decodeStartedAt = System.nanoTime();
            String responseBody = decodeResponseBody(response.body());
            long decodeNanos = elapsedNanos(decodeStartedAt);
            long parseJsonStartedAt = System.nanoTime();
            ChatCompletionResponse parsed = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            long jsonParseNanos = elapsedNanos(parseJsonStartedAt);
            long parseNanos = System.nanoTime() - parseStartedAt;
            latencyBreakdownBuilder
                    .responseDecodeLatencyNanos(decodeNanos)
                    .responseJsonParseLatencyNanos(jsonParseNanos)
                    .responseParseLatencyNanos(parseNanos)
                    .responseParseLatencyMs(toMillis(parseNanos));
            if (parsed.choices() == null || parsed.choices().isEmpty()) {
                long latencyMs = toMillis(System.nanoTime() - startedAt);
                log.error("LLM generation returned no choices model={} latencyMs={} metadata={}",
                        model, latencyMs, metadata);
                throw new GenerationException("Generation API returned no choices");
            }

            Choice firstChoice = parsed.choices().getFirst();
            String content = firstChoice.message() == null ? null : firstChoice.message().content();
            if (isBlank(content)) {
                long latencyMs = toMillis(System.nanoTime() - startedAt);
                log.error("LLM generation returned empty content model={} latencyMs={} metadata={}",
                        model, latencyMs, metadata);
                throw new GenerationException("Generation API returned empty content");
            }

            Map<String, String> responseMetadata = new LinkedHashMap<>();
            responseMetadata.put("httpStatus", Integer.toString(response.statusCode()));
            if (!isBlank(parsed.id())) {
                responseMetadata.put("responseId", parsed.id());
            }
            if (!isBlank(firstChoice.finishReason())) {
                responseMetadata.put("finishReason", firstChoice.finishReason());
            }

            Usage usage = parsed.usage();
            GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(latencyBreakdownBuilder, startedAt);
            log.info("LLM generation completed model={} responseId={} latencyMs={} requestBuildMs={} httpMs={} parseMs={} retryBackoffMs={} requestBytes={} responseBytes={} httpStatus={} promptTokens={} completionTokens={} metadata={}",
                    parsed.model(), parsed.id(), latencyBreakdown.totalLatencyMs(),
                    latencyBreakdown.getRequestBuildLatencyMs(),
                    latencyBreakdown.getHttpRoundTripLatencyMs(),
                    latencyBreakdown.getResponseParseLatencyMs(),
                    latencyBreakdown.getRetryBackoffLatencyMs(),
                    latencyBreakdown.getRequestBytes(),
                    latencyBreakdown.getResponseBytes(),
                    latencyBreakdown.getHttpStatusCode(),
                    usage == null ? null : usage.promptTokens(),
                    usage == null ? null : usage.completionTokens(),
                    metadata);

            return GenerationResult.builder()
                    .content(content.trim())
                    .model(parsed.model())
                    .requestId(parsed.id())
                    .finishReason(firstChoice.finishReason())
                    .promptTokens(usage == null ? null : usage.promptTokens())
                    .completionTokens(usage == null ? null : usage.completionTokens())
                    .totalTokens(usage == null ? null : usage.totalTokens())
                    .latencyMs(latencyBreakdown.totalLatencyMs())
                    .latencyBreakdown(latencyBreakdown)
                    .responseMetadata(responseMetadata)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("LLM generation interrupted model={} metadata={}", model, metadata);
            throw new GenerationException("Generation request interrupted", e);
        } catch (IOException e) {
            log.error("LLM generation I/O failed model={} metadata={}: {}", model, metadata, e.getMessage());
            throw new GenerationException("Generation request failed: " + e.getMessage(), e);
        } catch (GenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("LLM generation failed unexpectedly model={} metadata={}: {}", model, metadata, e.getMessage());
            throw new GenerationException("Generation request failed unexpectedly: " + e.getMessage(), e);
        }
    }

    private List<Message> buildMessages(GenerationRequest request) {
        List<Message> messages = new ArrayList<>();
        if (!isBlank(request.getSystemPrompt())) {
            messages.add(new Message("system", request.getSystemPrompt().trim()));
        }
        messages.add(new Message("user", request.getUserPrompt().trim()));
        return messages;
    }

    private void validate(GenerationProperties properties) {
        if (isBlank(properties.getBaseUrl())) {
            throw new IllegalStateException("vortex.kernel.generation.base-url must be configured when generation is enabled");
        }
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("vortex.kernel.generation.api-key must be configured when generation is enabled");
        }
        if (isBlank(properties.getModel())) {
            throw new IllegalStateException("vortex.kernel.generation.model must be configured when generation is enabled");
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 512) {
            return value;
        }
        return value.substring(0, 512) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private long elapsedNanos(long startedAt) {
        return System.nanoTime() - startedAt;
    }

    private long toMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private String decodeResponseBody(byte[] responseBody) {
        return responseBody == null ? "" : new String(responseBody, StandardCharsets.UTF_8);
    }

    private GenerationLatencyBreakdown buildLatencyBreakdown(
            GenerationLatencyBreakdown.GenerationLatencyBreakdownBuilder builder,
            long startedAt) {
        long totalNanos = elapsedNanos(startedAt);
        return builder
                .retryBackoffLatencyMs(0L)
                .retryBackoffLatencyNanos(0L)
                .totalLatencyMs(toMillis(totalNanos))
                .totalLatencyNanos(totalNanos)
                .build();
    }

    record ChatCompletionRequest(
            String model,
            List<Message> messages,
            double temperature,
            int max_tokens) {
    }

    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(
            String id,
            String model,
            List<Choice> choices,
            Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(
            MessageContent message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageContent(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
