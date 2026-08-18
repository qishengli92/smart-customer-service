package com.cs.knowledge.seed;

import com.cs.knowledge.ingest.KnowledgeIndexPipeline;
import com.cs.knowledge.milvus.MilvusKnowledgeStore;
import com.cs.knowledge.persistence.entity.CsKnowledgeDocEntity;
import com.cs.knowledge.persistence.repo.CsKnowledgeDocRepository;
import com.cs.knowledge.retrieval.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * FAQ 种子：写入 PG 后走解析分块 Embedding 管线进入 {@code cs_knowledge}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestService {

    private final CsKnowledgeDocRepository docRepository;
    private final KnowledgeIndexPipeline indexPipeline;
    private final MilvusKnowledgeStore milvusKnowledgeStore;

    /**
     * 空库时插入种子 FAQ；并对尚未切片的文档建索引。
     *
     * @return 本次成功索引的文档数
     */
    public int seedFaqToMilvus() {
        if (docRepository.count() == 0) {
            Instant now = Instant.now();
            for (FaqSeedKnowledge.SeedFaq faq : FaqSeedKnowledge.allFaqs()) {
                docRepository.save(CsKnowledgeDocEntity.builder()
                        .docId(faq.id())
                        .title(faq.title())
                        .category(faq.category())
                        .content(faq.content())
                        .source("种子FAQ")
                        .sourceType("FAQ")
                        .tags(String.join(",", faq.keywords()))
                        .status("DRAFT")
                        .chunkCount(0)
                        .version(1)
                        .isActive(true)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
            log.info("Inserted {} FAQ seed documents into PostgreSQL", FaqSeedKnowledge.allFaqs().size());
        }

        int indexed = 0;
        List<CsKnowledgeDocEntity> pending = docRepository.findUnindexed();
        if (pending.isEmpty()) {
            log.info("No unindexed knowledge docs");
            return 0;
        }
        for (CsKnowledgeDocEntity doc : pending) {
            try {
                indexPipeline.reindex(doc.getDocId());
                indexed++;
            } catch (Exception e) {
                log.warn("Seed index failed for {}: {}", doc.getDocId(), e.getMessage());
            }
        }
        milvusKnowledgeStore.flushQuietly();
        log.info("Knowledge seed ingest done: indexed={}", indexed);
        return indexed;
    }

    /**
     * 运维强制重刷 6 条种子 FAQ（按固定 ID upsert 后重建索引）。
     */
    public int upsertSeedFaqs() {
        Instant now = Instant.now();
        int n = 0;
        for (FaqSeedKnowledge.SeedFaq faq : FaqSeedKnowledge.allFaqs()) {
            CsKnowledgeDocEntity doc = docRepository.findById(faq.id()).orElse(null);
            if (doc == null) {
                doc = CsKnowledgeDocEntity.builder()
                        .docId(faq.id())
                        .createdAt(now)
                        .version(1)
                        .build();
            }
            doc.setTitle(faq.title());
            doc.setCategory(faq.category());
            doc.setContent(faq.content());
            doc.setSource("种子FAQ");
            doc.setSourceType("FAQ");
            doc.setTags(String.join(",", faq.keywords()));
            doc.setIsActive(true);
            doc.setChunkCount(0);
            doc.setUpdatedAt(now);
            if (doc.getVersion() == null) {
                doc.setVersion(1);
            }
            docRepository.save(doc);
            try {
                indexPipeline.reindex(faq.id());
                n++;
            } catch (Exception e) {
                log.warn("Upsert seed {} failed: {}", faq.id(), e.getMessage());
            }
        }
        milvusKnowledgeStore.flushQuietly();
        return n;
    }

    /** 兼容旧调用：种子 FAQ 的内存切片（不经 PG）。 */
    public List<KnowledgeChunk> seedChunks() {
        return FaqSeedKnowledge.allChunks();
    }
}
