package com.cs.gateway.controller;

import com.cs.knowledge.ingest.KnowledgeDocumentService;
import com.cs.knowledge.persistence.entity.CsKnowledgeChunkEntity;
import com.cs.knowledge.persistence.entity.CsKnowledgeDocEntity;
import com.cs.knowledge.retrieval.KnowledgeChunk;
import com.cs.knowledge.retrieval.KnowledgeRetrievalService;
import com.cs.knowledge.seed.KnowledgeIngestService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理与检索试跑。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class KnowledgeController {

    private final KnowledgeIngestService knowledgeIngestService;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeRetrievalService retrievalService;

    @PostMapping("/seed")
    public Mono<Map<String, Object>> seedFaq() {
        return Mono.fromCallable(() -> {
            int count = knowledgeIngestService.upsertSeedFaqs();
            return Map.<String, Object>of(
                    "status", "ok",
                    "upserted", count,
                    "message", "FAQ seeds indexed to PostgreSQL + Milvus"
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/docs")
    public Mono<List<Map<String, Object>>> listDocs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return Mono.fromCallable(() -> documentService.list(keyword, category, status).stream()
                        .map(documentService::toView)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/docs/{docId}")
    public Mono<Map<String, Object>> getDoc(@PathVariable String docId) {
        return Mono.fromCallable(() -> documentService.toView(documentService.get(docId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/docs")
    public Mono<Map<String, Object>> createDoc(@RequestBody DocRequest body) {
        return Mono.fromCallable(() -> {
            CsKnowledgeDocEntity doc = documentService.createArticle(
                    body.getTitle(), body.getCategory(), body.getTags(),
                    body.getContent(), body.getSourceType(),
                    body.getIndexNow() == null || body.getIndexNow());
            return documentService.toView(doc);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/docs/{docId}")
    public Mono<Map<String, Object>> updateDoc(@PathVariable String docId, @RequestBody DocRequest body) {
        return Mono.fromCallable(() -> {
            boolean reindex = body.getReindex() == null || body.getReindex();
            CsKnowledgeDocEntity doc = documentService.updateArticle(
                    docId, body.getTitle(), body.getCategory(), body.getTags(),
                    body.getContent(), body.getActive(), reindex);
            return documentService.toView(doc);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/docs/{docId}")
    public Mono<Map<String, Object>> deleteDoc(@PathVariable String docId) {
        return Mono.fromCallable(() -> {
            documentService.delete(docId);
            return Map.<String, Object>of("status", "ok", "docId", docId);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/docs/{docId}/publish")
    public Mono<Map<String, Object>> publish(@PathVariable String docId, @RequestBody(required = false) ActiveRequest body) {
        boolean active = body == null || body.getActive() == null || body.getActive();
        return Mono.fromCallable(() -> documentService.toView(documentService.setActive(docId, active)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/docs/{docId}/reindex")
    public Mono<Map<String, Object>> reindex(@PathVariable String docId) {
        return Mono.fromCallable(() -> documentService.toView(documentService.reindex(docId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/docs/{docId}/chunks")
    public Mono<List<Map<String, Object>>> listChunks(@PathVariable String docId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (CsKnowledgeChunkEntity c : documentService.chunks(docId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chunkId", c.getChunkId());
                m.put("docId", c.getDocId());
                m.put("ordinal", c.getOrdinal());
                m.put("heading", c.getHeading());
                m.put("content", c.getContent());
                m.put("tokenCount", c.getTokenCount());
                out.add(m);
            }
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(value = "/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(
            @RequestPart("file") FilePart file,
            @RequestPart(value = "title", required = false) String title,
            @RequestPart(value = "category", required = false) String category) {
        String filename = file.filename();
        return DataBufferUtils.join(file.content())
                .flatMap(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return Mono.fromCallable(() -> documentService.toView(
                                    documentService.upload(filename, bytes, title, category)))
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    @PostMapping("/search/test")
    public Mono<Map<String, Object>> searchTest(@RequestBody SearchRequest body) {
        return Mono.fromCallable(() -> {
            String query = body.getQuery();
            int topK = body.getTopK() != null ? body.getTopK() : 5;
            List<KnowledgeChunk> hits = retrievalService.retrieveHybrid(query, topK, 0.0);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (KnowledgeChunk hit : hits) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chunkId", hit.getChunkId());
                m.put("docId", hit.getDocId());
                m.put("title", hit.getSourceDoc());
                m.put("heading", hit.getHeading());
                m.put("content", hit.getContent());
                m.put("score", hit.getScore());
                m.put("vectorScore", hit.getVectorScore());
                m.put("keywordScore", hit.getKeywordScore());
                m.put("rerankScore", hit.getRerankScore());
                m.put("category", hit.getMetadata() != null ? hit.getMetadata().get("category") : null);
                rows.add(m);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("hits", rows);
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Data
    public static class DocRequest {
        private String title;
        private String category;
        private String tags;
        private String content;
        private String sourceType;
        private Boolean indexNow;
        private Boolean reindex;
        private Boolean active;
    }

    @Data
    public static class ActiveRequest {
        private Boolean active;
    }

    @Data
    public static class SearchRequest {
        private String query;
        private Integer topK;
    }
}
