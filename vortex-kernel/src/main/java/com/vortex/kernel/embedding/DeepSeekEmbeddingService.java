package com.vortex.kernel.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.exception.EmbeddingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Cloud embedding service backed by DeepSeek's embedding API.
 *
 * Model : deepseek-embedding
 * Dimension : 1024
 * Endpoint  : https://api.deepseek.com/embeddings  (OpenAI-compatible)
 *
 * Activated when vortex.kernel.embedding.cloud.enabled=true.
 * Used by HMC for L2 (Milvus) upsert and search; L1 continues to use
 * the local BGE-Small model for fast in-process scoring.
 *
 * Set DEEPSEEK_API_KEY environment variable (or cloud.api-key in application.yml).
 */
@Slf4j
@Service("cloudEmbeddingService")
@ConditionalOnProperty(name = "vortex.kernel.embedding.cloud.enabled", havingValue = "true")
public class DeepSeekEmbeddingService implements EmbeddingService {

    private static final int DIMENSION = 1024;
    private static final String ENDPOINT = "https://api.deepseek.com/embeddings";
    private static final String MODEL = "deepseek-embedding";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DeepSeekEmbeddingService(
            @Value("${vortex.kernel.embedding.cloud.api-key}") String apiKey,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("DeepSeek embedding service initialized (model={}, dim={})", MODEL, DIMENSION);
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = objectMapper.writeValueAsString(new EmbeddingRequest(MODEL, text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("DeepSeek API error status={} body={}", response.statusCode(), response.body());
                throw new EmbeddingException("DeepSeek API error status=" + response.statusCode());
            }

            EmbeddingResponse resp = objectMapper.readValue(response.body(), EmbeddingResponse.class);
            if (resp.data() == null || resp.data().isEmpty()) {
                log.error("DeepSeek returned empty data for text len={}", text.length());
                throw new EmbeddingException("DeepSeek returned empty embedding payload");
            }

            List<Double> raw = resp.data().get(0).embedding();
            float[] result = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) result[i] = raw.get(i).floatValue();
            return l2Normalize(result);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DeepSeek embedding interrupted: {}", e.getMessage());
            throw new EmbeddingException("DeepSeek embedding interrupted", e);
        } catch (Exception e) {
            log.error("DeepSeek embedding failed for text len={}: {}", text.length(), e.getMessage());
            throw new EmbeddingException("DeepSeek embedding failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        // DeepSeek supports batch input; for simplicity, call sequentially for now.
        // TODO: upgrade to single batch request when throughput becomes a concern.
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    private static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    // ---- JSON records (OpenAI-compatible schema) ----

    record EmbeddingRequest(String model, String input) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<EmbeddingData> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(List<Double> embedding, int index) {}
}
