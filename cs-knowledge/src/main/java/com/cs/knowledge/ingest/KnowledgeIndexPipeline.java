package com.cs.knowledge.ingest;

import com.cs.infra.config.MilvusProperties;
import com.cs.infra.embedding.DashScopeEmbeddingService;
import com.cs.knowledge.chunk.HeadingAwareChunker;
import com.cs.knowledge.milvus.MilvusKnowledgeStore;
import com.cs.knowledge.parse.DocumentParseService;
import com.cs.knowledge.persistence.entity.CsKnowledgeChunkEntity;
import com.cs.knowledge.persistence.entity.CsKnowledgeDocEntity;
import com.cs.knowledge.persistence.repo.CsKnowledgeChunkRepository;
import com.cs.knowledge.persistence.repo.CsKnowledgeDocRepository;
import com.cs.knowledge.retrieval.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 → 分块 → Embedding → Milvus upsert，并回写 PG chunk。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexPipeline {

    private final DocumentParseService parseService;
    private final HeadingAwareChunker chunker;
    private final DashScopeEmbeddingService embeddingService;
    private final MilvusKnowledgeStore milvusKnowledgeStore;
    private final MilvusProperties milvusProperties;
    private final CsKnowledgeDocRepository docRepository;
    private final CsKnowledgeChunkRepository chunkRepository;

    @Transactional
    public int reindex(String docId) {
        CsKnowledgeDocEntity doc = docRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
        return reindex(doc);
    }

    @Transactional
    public int reindex(CsKnowledgeDocEntity doc) {
        doc.setStatus("INDEXING");
        doc.setIndexError(null);
        doc.setUpdatedAt(Instant.now());
        docRepository.save(doc);

        try {
            String text = resolveText(doc);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("未能提取文本（扫描件需 OCR，本轮不支持）");
            }
            doc.setParsedText(text);

            List<HeadingAwareChunker.ChunkDraft> drafts =
                    chunker.split(doc.getTitle(), doc.getSourceType(), text);
            if (drafts.isEmpty()) {
                throw new IllegalStateException("分块结果为空");
            }

            milvusKnowledgeStore.deleteByDocId(doc.getDocId());
            chunkRepository.deleteByDocId(doc.getDocId());

            List<KnowledgeChunk> chunks = new ArrayList<>(drafts.size());
            List<String> embedTexts = new ArrayList<>(drafts.size());
            Instant now = Instant.now();
            List<CsKnowledgeChunkEntity> rows = new ArrayList<>(drafts.size());

            for (HeadingAwareChunker.ChunkDraft draft : drafts) {
                String chunkId = chunkId(doc.getDocId(), draft.ordinal());
                Map<String, String> meta = new HashMap<>();
                if (doc.getCategory() != null) {
                    meta.put("category", doc.getCategory());
                }
                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .chunkId(chunkId)
                        .docId(doc.getDocId())
                        .sourceDoc(doc.getTitle())
                        .heading(draft.heading())
                        .content(draft.content())
                        .metadata(meta)
                        .build();
                chunks.add(chunk);

                String heading = draft.heading() != null ? draft.heading() : "";
                embedTexts.add(doc.getTitle() + "\n" + heading + "\n" + draft.content());

                rows.add(CsKnowledgeChunkEntity.builder()
                        .chunkId(chunkId)
                        .docId(doc.getDocId())
                        .ordinal(draft.ordinal())
                        .heading(draft.heading())
                        .content(draft.content())
                        .tokenCount(draft.tokenCount())
                        .createdAt(now)
                        .build());
            }

            int dim = milvusProperties.getEmbeddingDimension();
            List<float[]> vectors = embeddingService.embedBatch(embedTexts, dim, "document");
            milvusKnowledgeStore.upsertChunks(chunks, vectors);
            chunkRepository.saveAll(rows);

            doc.setChunkCount(chunks.size());
            doc.setStatus("READY");
            doc.setIndexError(null);
            doc.setUpdatedAt(Instant.now());
            if (doc.getVersion() == null) {
                doc.setVersion(1);
            }
            docRepository.save(doc);
            log.info("Indexed knowledge doc {}: chunks={}", doc.getDocId(), chunks.size());
            return chunks.size();
        } catch (Exception e) {
            log.warn("Index failed for {}: {}", doc.getDocId(), e.getMessage());
            doc.setStatus("FAILED");
            doc.setIndexError(e.getMessage());
            doc.setUpdatedAt(Instant.now());
            docRepository.save(doc);
            return 0;
        }
    }

    @Transactional
    public void removeVectors(String docId) {
        try {
            milvusKnowledgeStore.deleteByDocId(docId);
        } catch (Exception e) {
            log.warn("Milvus delete {} failed: {}", docId, e.getMessage());
        }
        chunkRepository.deleteByDocId(docId);
    }

    private String resolveText(CsKnowledgeDocEntity doc) {
        if ("FILE".equalsIgnoreCase(doc.getSourceType())
                && doc.getFilePath() != null && !doc.getFilePath().isBlank()) {
            return parseService.parseFile(Path.of(doc.getFilePath()), doc.getFileName());
        }
        String content = doc.getContent();
        if (content != null && !content.isBlank()) {
            return parseService.normalizeText(content);
        }
        if (doc.getFilePath() != null && Files.isRegularFile(Path.of(doc.getFilePath()))) {
            return parseService.parseFile(Path.of(doc.getFilePath()), doc.getFileName());
        }
        return parseService.normalizeText(doc.getParsedText());
    }

    static String chunkId(String docId, int ordinal) {
        String id = docId + "_" + ordinal;
        return id.length() <= 64 ? id : id.substring(0, 64);
    }
}
