package com.cs.knowledge.retrieval;

import com.cs.infra.config.MilvusProperties;
import com.cs.infra.embedding.DashScopeEmbeddingService;
import com.cs.knowledge.milvus.MilvusKnowledgeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识检索：仅通过 Milvus 向量检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final MilvusProperties milvusProperties;
    private final MilvusKnowledgeStore milvusKnowledgeStore;
    private final DashScopeEmbeddingService embeddingService;

    public List<KnowledgeChunk> retrieve(String query, String collection,
                                         int topK, double threshold) {
        log.info("Knowledge retrieval: query={}, collection={}, topK={}, threshold={}",
                query != null ? query.substring(0, Math.min(30, query.length())) : "",
                collection, topK, threshold);

        try {
            List<KnowledgeChunk> hits = retrieveFromMilvus(query, topK, threshold);
            log.info("Milvus hits: {}", hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("Milvus retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<KnowledgeChunk> retrieveFromAll(String query, int topK, double threshold) {
        return retrieve(query, milvusProperties.faqCollectionName(), topK, threshold);
    }

    public String formatAsContext(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "未检索到相关知识。";
        }
        StringBuilder sb = new StringBuilder("以下是从知识库检索到的相关信息：\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            sb.append(String.format("【参考%d】%s\n\n", i + 1, chunks.get(i).toContextText()));
        }
        sb.append("请基于以上信息回答用户问题。如果检索结果不足以回答，请坦诚告知。");
        return sb.toString();
    }

    private List<KnowledgeChunk> retrieveFromMilvus(String query, int topK, double threshold) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (milvusProperties.getHost() == null || milvusProperties.getHost().isBlank()) {
            return List.of();
        }
        float[] vector = embeddingService.embed(query, milvusProperties.getEmbeddingDimension());
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        return milvusKnowledgeStore.search(vector, topK, threshold);
    }
}
