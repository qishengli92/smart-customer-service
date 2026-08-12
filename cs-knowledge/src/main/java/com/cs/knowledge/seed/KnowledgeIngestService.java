package com.cs.knowledge.seed;

import com.cs.infra.config.MilvusProperties;
import com.cs.infra.embedding.DashScopeEmbeddingService;
import com.cs.knowledge.milvus.MilvusKnowledgeStore;
import com.cs.knowledge.retrieval.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 将种子 FAQ 向量化并写入 Milvus collection {@code cs_faq}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestService {

    private final DashScopeEmbeddingService embeddingService;
    private final MilvusKnowledgeStore milvusKnowledgeStore;
    private final MilvusProperties milvusProperties;

    /**
     * @return 写入条数；失败时返回 0（不抛到启动流程）
     */
    public int seedFaqToMilvus() {
        List<KnowledgeChunk> chunks = FaqSeedKnowledge.allChunks();
        if (chunks.isEmpty()) {
            log.warn("No FAQ seed chunks to ingest");
            return 0;
        }

        List<String> texts = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            // title + content 一起编码，提升检索召回
            texts.add(chunk.getSourceDoc() + "\n" + chunk.getContent());
        }

        int dim = milvusProperties.getEmbeddingDimension();
        log.info("Embedding {} FAQ chunks (dim={}) for Milvus ingest...", texts.size(), dim);
        List<float[]> vectors = embeddingService.embedBatch(texts, dim);

        int written = milvusKnowledgeStore.upsertChunks(chunks, vectors);
        log.info("FAQ seed ingest done: collection={}, count={}",
                milvusProperties.faqCollectionName(), written);
        return written;
    }
}
