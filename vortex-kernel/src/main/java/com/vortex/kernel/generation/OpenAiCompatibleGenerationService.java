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
    public static final String ERROR_GENERATION_TIMEOUT = "generation_timeout";
    public static final String ERROR_GENERATION_CONNECTION_RESET = "generation_connection_reset";
    public static final String ERROR_GENERATION_HTTP_429 = "generation_http_429";
    public static final String ERROR_GENERATION_HTTP_5XX = "generation_http_5xx";
    public static final String ERROR_GENERATION_HTTP_ERROR = "generation_http_error";
    public static final String ERROR_GENERATION_PARSE_ERROR = "generation_parse_error";
    public static final String ERROR_GENERATION_EMPTY_RESPONSE = "generation_empty_response";
    public static final String ERROR_GENERATION_INTERRUPTED = "generation_interrupted";
    public static final String ERROR_GENERATION_UNEXPECTED = "generation_unexpected";

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
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        long retryBackoffNanos = 0L;

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

            HttpResponse<byte[]> response = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                latencyBreakdownBuilder.attemptCount(attempt);
                try {
                    long httpStartedAt = System.nanoTime();
                    response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
                    long httpRoundTripNanos = elapsedNanos(httpStartedAt);
                    latencyBreakdownBuilder
                            .httpRoundTripLatencyNanos(httpRoundTripNanos)
                            .httpRoundTripLatencyMs(toMillis(httpRoundTripNanos))
                            .httpStatusCode(response.statusCode())
                            .responseBytes(response.body() == null ? 0 : response.body().length);
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        break;
                    }
                    String errorType = classifyHttpError(response.statusCode());
                    if (!isTransientHttpStatus(response.statusCode()) || attempt == maxAttempts) {
                        GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                                latencyBreakdownBuilder,
                                startedAt,
                                retryBackoffNanos);
                        log.error("LLM generation failed status={} model={} latencyMs={} requestBuildMs={} httpMs={} parseMs={} retryBackoffMs={} attempts={} metadata={} body={}",
                                response.statusCode(), model, latencyBreakdown.totalLatencyMs(),
                                latencyBreakdown.getRequestBuildLatencyMs(),
                                latencyBreakdown.getHttpRoundTripLatencyMs(),
                                latencyBreakdown.getResponseParseLatencyMs(),
                                latencyBreakdown.getRetryBackoffLatencyMs(),
                                latencyBreakdown.getAttemptCount(),
                                metadata, abbreviate(decodeResponseBody(response.body())));
                        throw newGenerationException(
                                "Generation API error status=" + response.statusCode(),
                                null,
                                errorType,
                                isTransientHttpStatus(response.statusCode()),
                                latencyBreakdown);
                    }
                    retryBackoffNanos += backoffBeforeRetry(attempt, model, metadata, errorType);
                } catch (IOException e) {
                    String errorType = classifyIOException(e);
                    boolean transientError = isTransientIoError(errorType);
                    if (!transientError || attempt == maxAttempts) {
                        GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                                latencyBreakdownBuilder,
                                startedAt,
                                retryBackoffNanos);
                        log.error("LLM generation I/O failed model={} attempts={} retryBackoffMs={} metadata={}: {}",
                                model, latencyBreakdown.getAttemptCount(),
                                latencyBreakdown.getRetryBackoffLatencyMs(), metadata, e.getMessage());
                        throw newGenerationException(
                                "Generation request failed: " + e.getMessage(),
                                e,
                                errorType,
                                transientError,
                                latencyBreakdown);
                    }
                    retryBackoffNanos += backoffBeforeRetry(attempt, model, metadata, errorType);
                }
            }
            if (response == null) {
                GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                        latencyBreakdownBuilder,
                        startedAt,
                        retryBackoffNanos);
                throw newGenerationException(
                        "Generation request failed unexpectedly: no HTTP response",
                        null,
                        ERROR_GENERATION_UNEXPECTED,
                        false,
                        latencyBreakdown);
            }

            long parseStartedAt = System.nanoTime();
            long decodeStartedAt = System.nanoTime();
            String responseBody = decodeResponseBody(response.body());
            long decodeNanos = elapsedNanos(decodeStartedAt);
            long parseJsonStartedAt = System.nanoTime();
            ChatCompletionResponse parsed;
            try {
                parsed = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            } catch (IOException e) {
                GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                        latencyBreakdownBuilder,
                        startedAt,
                        retryBackoffNanos);
                throw newGenerationException(
                        "Generation response parse failed: " + e.getMessage(),
                        e,
                        ERROR_GENERATION_PARSE_ERROR,
                        false,
                        latencyBreakdown);
            }
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
                GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                        latencyBreakdownBuilder,
                        startedAt,
                        retryBackoffNanos);
                throw newGenerationException(
                        "Generation API returned no choices",
                        null,
                        ERROR_GENERATION_EMPTY_RESPONSE,
                        false,
                        latencyBreakdown);
            }

            Choice firstChoice = parsed.choices().getFirst();
            String content = firstChoice.message() == null ? null : firstChoice.message().content();
            if (isBlank(content)) {
                long latencyMs = toMillis(System.nanoTime() - startedAt);
                log.error("LLM generation returned empty content model={} latencyMs={} metadata={}",
                        model, latencyMs, metadata);
                GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                        latencyBreakdownBuilder,
                        startedAt,
                        retryBackoffNanos);
                throw newGenerationException(
                        "Generation API returned empty content",
                        null,
                        ERROR_GENERATION_EMPTY_RESPONSE,
                        false,
                        latencyBreakdown);
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
            GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                    latencyBreakdownBuilder,
                    startedAt,
                    retryBackoffNanos);
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
            GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                    latencyBreakdownBuilder,
                    startedAt,
                    retryBackoffNanos);
            throw newGenerationException(
                    "Generation request interrupted",
                    e,
                    ERROR_GENERATION_INTERRUPTED,
                    false,
                    latencyBreakdown);
        } catch (IOException e) {
            log.error("LLM generation request serialization failed model={} metadata={}: {}",
                    model, metadata, e.getMessage());
            GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                    latencyBreakdownBuilder,
                    startedAt,
                    retryBackoffNanos);
            throw newGenerationException(
                    "Generation request serialization failed: " + e.getMessage(),
                    e,
                    ERROR_GENERATION_UNEXPECTED,
                    false,
                    latencyBreakdown);
        } catch (GenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("LLM generation failed unexpectedly model={} metadata={}: {}", model, metadata, e.getMessage());
            GenerationLatencyBreakdown latencyBreakdown = buildLatencyBreakdown(
                    latencyBreakdownBuilder,
                    startedAt,
                    retryBackoffNanos);
            throw newGenerationException(
                    "Generation request failed unexpectedly: " + e.getMessage(),
                    e,
                    ERROR_GENERATION_UNEXPECTED,
                    false,
                    latencyBreakdown);
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
        if (properties.getMaxRetries() < 0) {
            throw new IllegalStateException("vortex.kernel.generation.max-retries must be >= 0");
        }
        if (properties.getRetryInitialBackoff() == null || properties.getRetryInitialBackoff().isNegative()) {
            throw new IllegalStateException("vortex.kernel.generation.retry-initial-backoff must be >= 0");
        }
        if (properties.getRetryBackoffMultiplier() < 1.0d) {
            throw new IllegalStateException("vortex.kernel.generation.retry-backoff-multiplier must be >= 1.0");
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
            long startedAt,
            long retryBackoffNanos) {
        long totalNanos = elapsedNanos(startedAt);
        return builder
                .retryBackoffLatencyMs(toMillis(retryBackoffNanos))
                .retryBackoffLatencyNanos(retryBackoffNanos)
                .totalLatencyMs(toMillis(totalNanos))
                .totalLatencyNanos(totalNanos)
                .build();
    }

    private long backoffBeforeRetry(int attempt, String model, Map<String, String> metadata, String errorType)
            throws InterruptedException {
        Duration backoff = retryBackoff(attempt);
        log.warn("LLM generation transient failure errorType={} attempt={} nextBackoffMs={} model={} metadata={}",
                errorType, attempt, backoff.toMillis(), model, metadata);
        long startedAt = System.nanoTime();
        if (!backoff.isZero()) {
            Thread.sleep(backoff.toMillis());
        }
        return elapsedNanos(startedAt);
    }

    private Duration retryBackoff(int attempt) {
        long initialMillis = properties.getRetryInitialBackoff().toMillis();
        double multiplier = Math.pow(properties.getRetryBackoffMultiplier(), Math.max(0, attempt - 1));
        return Duration.ofMillis(Math.max(0L, Math.round(initialMillis * multiplier)));
    }

    private boolean isTransientHttpStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private String classifyHttpError(int statusCode) {
        if (statusCode == 429) {
            return ERROR_GENERATION_HTTP_429;
        }
        if (statusCode >= 500) {
            return ERROR_GENERATION_HTTP_5XX;
        }
        return ERROR_GENERATION_HTTP_ERROR;
    }

    private String classifyIOException(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("timeout") || message.contains("timed out")) {
            return ERROR_GENERATION_TIMEOUT;
        }
        if (message.contains("connection reset") || message.contains("connection aborted")) {
            return ERROR_GENERATION_CONNECTION_RESET;
        }
        return ERROR_GENERATION_UNEXPECTED;
    }

    private boolean isTransientIoError(String errorType) {
        return ERROR_GENERATION_TIMEOUT.equals(errorType)
                || ERROR_GENERATION_CONNECTION_RESET.equals(errorType);
    }

    private GenerationException newGenerationException(
            String message,
            Throwable cause,
            String errorType,
            boolean transientError,
            GenerationLatencyBreakdown latencyBreakdown) {
        return new GenerationException(message, cause, errorType, transientError, latencyBreakdown);
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
