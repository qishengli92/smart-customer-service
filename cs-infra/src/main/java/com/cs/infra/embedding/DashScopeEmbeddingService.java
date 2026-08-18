package com.cs.infra.embedding;

import com.cs.infra.model.DashScopeModelProperties;
import com.cs.infra.model.LlmModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope Embedding：知识入库 {@code document}、查询 {@code query}（原生 API 的 text_type）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeEmbeddingService {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int BATCH_SIZE = 10;

    private final DashScopeModelProperties properties;
    private final LlmModelConfig llmModelConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient.Builder webClientBuilder = WebClient.builder();

    public float[] embed(String text, int dimensions) {
        return embed(text, dimensions, "query");
    }

    public float[] embed(String text, int dimensions, String textType) {
        List<float[]> vectors = embedBatch(List.of(text), dimensions, textType);
        return vectors.isEmpty() ? new float[0] : vectors.get(0);
    }

    public List<float[]> embedBatch(List<String> texts, int dimensions) {
        return embedBatch(texts, dimensions, "document");
    }

    public List<float[]> embedBatch(List<String> texts, int dimensions, String textType) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String apiKey = llmModelConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY 未配置，无法生成 Embedding");
        }

        List<float[]> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + BATCH_SIZE));
            all.addAll(embedNativeBatch(batch, dimensions, textType, apiKey));
        }
        return all;
    }

    private List<float[]> embedNativeBatch(List<String> texts, int dimensions, String textType, String apiKey) {
        String nativeBase = properties.getNativeBaseUrl();
        if (nativeBase == null || nativeBase.isBlank()) {
            nativeBase = "https://dashscope.aliyuncs.com/api/v1";
        }
        if (nativeBase.endsWith("/")) {
            nativeBase = nativeBase.substring(0, nativeBase.length() - 1);
        }

        String model = properties.getEmbeddingModel() != null
                ? properties.getEmbeddingModel() : "text-embedding-v3";
        String type = textType == null || textType.isBlank() ? "document" : textType;

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("text_type", type);
        parameters.put("dimension", dimensions);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", Map.of("texts", texts));
        body.put("parameters", parameters);

        try {
            String raw = webClientBuilder.build()
                    .post()
                    .uri(nativeBase + "/services/embeddings/text-embedding/text-embedding")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode root = objectMapper.readTree(raw);
            JsonNode embeddings = root.path("output").path("embeddings");
            if (!embeddings.isArray() || embeddings.isEmpty()) {
                log.warn("Native embedding empty, fallback compatible: {}", raw);
                return embedCompatible(texts, dimensions, apiKey);
            }

            List<JsonNode> items = new ArrayList<>();
            embeddings.forEach(items::add);
            items.sort((a, b) -> Integer.compare(
                    a.path("text_index").asInt(0), b.path("text_index").asInt(0)));
            List<float[]> vectors = new ArrayList<>(items.size());
            for (JsonNode item : items) {
                JsonNode emb = item.path("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                vectors.add(vec);
            }
            log.debug("Embedding ok: model={}, texts={}, dim={}, text_type={}",
                    model, texts.size(), vectors.isEmpty() ? 0 : vectors.get(0).length, type);
            return vectors;
        } catch (Exception e) {
            log.warn("Native embedding failed, fallback compatible: {}", e.getMessage());
            return embedCompatible(texts, dimensions, apiKey);
        }
    }

    private List<float[]> embedCompatible(List<String> texts, int dimensions, String apiKey) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String model = properties.getEmbeddingModel() != null
                ? properties.getEmbeddingModel() : "text-embedding-v3";

        Map<String, Object> body = Map.of(
                "model", model,
                "input", texts,
                "dimensions", dimensions
        );

        try {
            String raw = webClientBuilder.build()
                    .post()
                    .uri(baseUrl + "/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Embedding 响应为空: " + raw);
            }

            List<JsonNode> items = new ArrayList<>();
            data.forEach(items::add);
            items.sort((a, b) -> Integer.compare(a.path("index").asInt(0), b.path("index").asInt(0)));
            List<float[]> vectors = new ArrayList<>(items.size());
            for (JsonNode item : items) {
                JsonNode emb = item.path("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                vectors.add(vec);
            }
            return vectors;
        } catch (Exception e) {
            throw new IllegalStateException("调用 DashScope Embedding 失败: " + e.getMessage(), e);
        }
    }
}
