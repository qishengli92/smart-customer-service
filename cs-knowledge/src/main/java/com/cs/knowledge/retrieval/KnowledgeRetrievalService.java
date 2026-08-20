package com.cs.knowledge.retrieval;

import com.cs.infra.config.MilvusProperties;
import com.cs.infra.embedding.DashScopeEmbeddingService;
import com.cs.knowledge.config.KnowledgeProperties;
import com.cs.knowledge.milvus.MilvusKnowledgeStore;
import com.cs.knowledge.persistence.repo.KnowledgeKeywordSearchDao;
import com.cs.knowledge.rerank.DashScopeRerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：向量 TopK + PG 关键词（库内过滤排序），RRF 融合后再 Rerank。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private static final int RRF_K = 60;

    private final MilvusProperties milvusProperties;
    private final KnowledgeProperties knowledgeProperties;
    private final MilvusKnowledgeStore milvusKnowledgeStore;
    private final DashScopeEmbeddingService embeddingService;
    private final DashScopeRerankService rerankService;
    private final KnowledgeKeywordSearchDao keywordSearchDao;

    public List<KnowledgeChunk> retrieve(String query, String collection, int topK, double threshold) {
        return retrieveHybrid(query, topK, threshold);
    }

    public List<KnowledgeChunk> retrieveFromAll(String query, int topK, double threshold) {
        return retrieveHybrid(query, topK, threshold);
    }

    // 混合检索
    public List<KnowledgeChunk> retrieveHybrid(String query, int topK, double threshold) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int recallK = knowledgeProperties.getRecallK() > 0 ? knowledgeProperties.getRecallK() : 20;
        int finalK = topK > 0 ? topK : milvusProperties.getTopK();

        List<KnowledgeChunk> vectorHits = retrieveFromMilvus(query, recallK, 0.0);
        List<KnowledgeChunk> keywordHits = keywordSearch(query, recallK);
        List<KnowledgeChunk> fused = rrfFuse(vectorHits, keywordHits, recallK);

        log.info("Knowledge hybrid retrieve: query={}, vector={}, keyword={}, fused={}",
                query.substring(0, Math.min(30, query.length())),
                vectorHits.size(), keywordHits.size(), fused.size());

        List<KnowledgeChunk> reranked = rerankService.rerank(query, fused);
        if (reranked.size() > finalK) {
            reranked = new ArrayList<>(reranked.subList(0, finalK));
        }
        if (threshold > 0 && !reranked.isEmpty() && reranked.get(0).getRerankScore() == null) {
            reranked.removeIf(c -> c.getScore() != null && c.getScore() < threshold);
        }
        return reranked;
    }

    public RagResult toRagResult(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return RagResult.empty();
        }
        StringBuilder sb = new StringBuilder("以下是从知识库检索到的相关信息：\n\n");
        List<KnowledgeCitation> citations = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk c = chunks.get(i);
            int n = i + 1;
            String category = c.getMetadata() != null ? c.getMetadata().getOrDefault("category", "") : "";
            String title = c.getSourceDoc() != null ? c.getSourceDoc() : "";
            String heading = c.getHeading() != null && !c.getHeading().isBlank() ? c.getHeading() : title;
            sb.append(String.format("[%d] %s%s\n%s\n\n",
                    n,
                    heading,
                    category.isBlank() ? "" : " / " + category,
                    c.getContent()));
            citations.add(KnowledgeCitation.builder()
                    .index(n)
                    .chunkId(c.getChunkId())
                    .docId(c.getDocId())
                    .title(title)
                    .heading(heading)
                    .category(category)
                    .score(c.getScore())
                    .build());
        }
        sb.append("请基于以上编号参考回答。在相关句末用 [1][2] 标注来源；不得引用未出现的编号。");
        return RagResult.builder()
                .context(sb.toString())
                .chunks(chunks)
                .citations(citations)
                .build();
    }

    public String formatAsContext(List<KnowledgeChunk> chunks) {
        return toRagResult(chunks).getContext();
    }

    // 向量检索
    private List<KnowledgeChunk> retrieveFromMilvus(String query, int topK, double threshold) {
        if (milvusProperties.getHost() == null || milvusProperties.getHost().isBlank()) {
            return List.of();
        }
        try {
            float[] vector = embeddingService.embed(query, milvusProperties.getEmbeddingDimension(), "query");
            if (vector == null || vector.length == 0) {
                return List.of();
            }
            return milvusKnowledgeStore.search(vector, topK, threshold);
        } catch (Exception e) {
            log.warn("Milvus retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<KnowledgeChunk> keywordSearch(String query, int topK) {
        try {
            return keywordSearchDao.search(query, topK);
        } catch (Exception e) {
            log.warn("Keyword search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<KnowledgeChunk> rrfFuse(List<KnowledgeChunk> vectorHits,
                                         List<KnowledgeChunk> keywordHits,
                                         int limit) {
        Map<String, KnowledgeChunk> byId = new LinkedHashMap<>();
        Map<String, Double> rrf = new HashMap<>();

        addRank(vectorHits, byId, rrf, true);
        addRank(keywordHits, byId, rrf, false);

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(rrf.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<KnowledgeChunk> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) {
            KnowledgeChunk src = byId.get(e.getKey());
            if (src == null) {
                continue;
            }
            out.add(KnowledgeChunk.builder()
                    .chunkId(src.getChunkId())
                    .docId(src.getDocId())
                    .sourceDoc(src.getSourceDoc())
                    .heading(src.getHeading())
                    .content(src.getContent())
                    .metadata(src.getMetadata())
                    .vectorScore(src.getVectorScore())
                    .keywordScore(src.getKeywordScore())
                    .score(e.getValue().floatValue())
                    .build());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private void addRank(List<KnowledgeChunk> hits, Map<String, KnowledgeChunk> byId,
                         Map<String, Double> rrf, boolean vectorLane) {
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeChunk hit = hits.get(i);
            if (hit.getChunkId() == null) {
                continue;
            }
            KnowledgeChunk existing = byId.get(hit.getChunkId());
            if (existing == null) {
                byId.put(hit.getChunkId(), hit);
            } else {
                if (vectorLane && hit.getVectorScore() != null) {
                    existing.setVectorScore(hit.getVectorScore());
                }
                if (!vectorLane && hit.getKeywordScore() != null) {
                    existing.setKeywordScore(hit.getKeywordScore());
                }
            }
            double add = 1.0 / (RRF_K + i + 1);
            rrf.merge(hit.getChunkId(), add, Double::sum);
        }
    }
}
