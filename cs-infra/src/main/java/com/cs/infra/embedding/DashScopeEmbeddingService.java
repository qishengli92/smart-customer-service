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
import java.util.List;
import java.util.Map;

/**
 * DashScope Embedding（OpenAI 兼容 /embeddings）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeEmbeddingService {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final DashScopeModelProperties properties;
    private final LlmModelConfig llmModelConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient.Builder webClientBuilder = WebClient.builder();

    public float[] embed(String text, int dimensions) {
        List<float[]> vectors = embedBatch(List.of(text), dimensions);
        return vectors.isEmpty() ? new float[0] : vectors.get(0);
    }

    public List<float[]> embedBatch(List<String> texts, int dimensions) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String apiKey = llmModelConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY 未配置，无法生成 Embedding");
        }

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

            List<float[]> vectors = new ArrayList<>(data.size());
            // 按 index 排序，保证与输入顺序一致
            List<JsonNode> items = new ArrayList<>();
            data.forEach(items::add);
            items.sort((a, b) -> Integer.compare(a.path("index").asInt(0), b.path("index").asInt(0)));
            for (JsonNode item : items) {
                JsonNode emb = item.path("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                vectors.add(vec);
            }
            log.debug("Embedding ok: model={}, texts={}, dim={}", model, texts.size(),
                    vectors.isEmpty() ? 0 : vectors.get(0).length);
            return vectors;
        } catch (Exception e) {
            throw new IllegalStateException("调用 DashScope Embedding 失败: " + e.getMessage(), e);
        }
    }
}
