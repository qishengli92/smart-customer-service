package com.cs.knowledge.rerank;

import com.cs.infra.model.DashScopeModelProperties;
import com.cs.infra.model.LlmModelConfig;
import com.cs.knowledge.config.KnowledgeProperties;
import com.cs.knowledge.retrieval.KnowledgeChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope {@code gte-rerank-v2}：对融合候选重排，失败时原样返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeRerankService {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final KnowledgeProperties knowledgeProperties;
    private final DashScopeModelProperties modelProperties;
    private final LlmModelConfig llmModelConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient.Builder webClientBuilder = WebClient.builder();

    public List<KnowledgeChunk> rerank(String query, List<KnowledgeChunk> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        KnowledgeProperties.Rerank cfg = knowledgeProperties.getRerank();
        if (cfg == null || !cfg.isEnabled()) {
            return candidates;
        }
        String apiKey = llmModelConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Rerank skipped: DASHSCOPE_API_KEY missing");
            return candidates;
        }

        int topN = Math.max(1, cfg.getTopN());
        List<String> documents = new ArrayList<>(candidates.size());
        for (KnowledgeChunk c : candidates) {
            documents.add(c.getContent() != null ? c.getContent() : "");
        }

        String nativeBase = modelProperties.getNativeBaseUrl();
        if (nativeBase == null || nativeBase.isBlank()) {
            nativeBase = "https://dashscope.aliyuncs.com/api/v1";
        }
        if (nativeBase.endsWith("/")) {
            nativeBase = nativeBase.substring(0, nativeBase.length() - 1);
        }
        String model = cfg.getModel() != null ? cfg.getModel() : "gte-rerank-v2";

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", Map.of("query", query, "documents", documents));
        body.put("parameters", Map.of("top_n", Math.min(topN, candidates.size()), "return_documents", false));

        try {
            String raw = webClientBuilder.build()
                    .post()
                    .uri(nativeBase + "/services/rerank/text-rerank/text-rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode results = objectMapper.readTree(raw).path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("Rerank empty response, keep fused order");
                return candidates.stream().limit(topN).toList();
            }

            List<KnowledgeChunk> ranked = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }
                float score = (float) item.path("relevance_score").asDouble(0);
                KnowledgeChunk src = candidates.get(index);
                ranked.add(KnowledgeChunk.builder()
                        .chunkId(src.getChunkId())
                        .docId(src.getDocId())
                        .sourceDoc(src.getSourceDoc())
                        .heading(src.getHeading())
                        .content(src.getContent())
                        .metadata(src.getMetadata())
                        .vectorScore(src.getVectorScore())
                        .keywordScore(src.getKeywordScore())
                        .rerankScore(score)
                        .score(score)
                        .build());
            }
            ranked.sort(Comparator.comparing(KnowledgeChunk::getRerankScore,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            if (ranked.isEmpty()) {
                return candidates.stream().limit(topN).toList();
            }
            log.debug("Reranked {} -> {} hits", candidates.size(), ranked.size());
            return ranked.size() > topN ? ranked.subList(0, topN) : ranked;
        } catch (Exception e) {
            log.warn("Rerank failed, fallback fused order: {}", e.getMessage());
            return candidates.stream().limit(topN).toList();
        }
    }
}
