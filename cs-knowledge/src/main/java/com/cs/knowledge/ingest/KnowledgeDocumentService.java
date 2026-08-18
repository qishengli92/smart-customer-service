package com.cs.knowledge.ingest;

import com.cs.common.util.IdGenerator;
import com.cs.knowledge.config.KnowledgeProperties;
import com.cs.knowledge.persistence.entity.CsKnowledgeChunkEntity;
import com.cs.knowledge.persistence.entity.CsKnowledgeDocEntity;
import com.cs.knowledge.persistence.repo.CsKnowledgeChunkRepository;
import com.cs.knowledge.persistence.repo.CsKnowledgeDocRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识库文档 CRUD、上下线、上传与重索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "doc", "docx", "md", "markdown", "txt", "html", "htm");

    private final CsKnowledgeDocRepository docRepository;
    private final CsKnowledgeChunkRepository chunkRepository;
    private final KnowledgeIndexPipeline indexPipeline;
    private final KnowledgeProperties knowledgeProperties;

    public List<CsKnowledgeDocEntity> list(String keyword, String category, String status) {
        return docRepository.search(blankToNull(keyword), blankToNull(category), blankToNull(status));
    }

    public CsKnowledgeDocEntity get(String docId) {
        return docRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
    }

    public List<CsKnowledgeChunkEntity> chunks(String docId) {
        get(docId);
        return chunkRepository.findByDocIdOrderByOrdinalAsc(docId);
    }

    public CsKnowledgeDocEntity createArticle(String title, String category, String tags,
                                              String content, String sourceType, boolean indexNow) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        Instant now = Instant.now();
        String type = sourceType == null || sourceType.isBlank() ? "ARTICLE" : sourceType.toUpperCase(Locale.ROOT);
        CsKnowledgeDocEntity doc = CsKnowledgeDocEntity.builder()
                .docId(IdGenerator.knowledgeDocId())
                .title(title.trim())
                .category(category)
                .content(content != null ? content : "")
                .source("admin")
                .sourceType(type)
                .tags(tags)
                .status(indexNow ? "INDEXING" : "DRAFT")
                .chunkCount(0)
                .version(1)
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        doc = docRepository.save(doc);
        if (indexNow) {
            return indexQuietly(doc.getDocId());
        }
        return doc;
    }

    public CsKnowledgeDocEntity updateArticle(String docId, String title, String category, String tags,
                                              String content, Boolean active, boolean reindex) {
        CsKnowledgeDocEntity doc = get(docId);
        if (title != null && !title.isBlank()) {
            doc.setTitle(title.trim());
        }
        if (category != null) {
            doc.setCategory(category);
        }
        if (tags != null) {
            doc.setTags(tags);
        }
        if (content != null) {
            doc.setContent(content);
        }
        if (active != null) {
            doc.setIsActive(active);
        }
        doc.setVersion(doc.getVersion() == null ? 2 : doc.getVersion() + 1);
        doc.setUpdatedAt(Instant.now());
        docRepository.save(doc);

        if (Boolean.FALSE.equals(doc.getIsActive())) {
            indexPipeline.removeVectors(docId);
            return get(docId);
        }
        if (reindex) {
            return indexQuietly(docId);
        }
        return get(docId);
    }

    @Transactional
    public void delete(String docId) {
        CsKnowledgeDocEntity doc = get(docId);
        indexPipeline.removeVectors(docId);
        if (doc.getFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(doc.getFilePath()));
            } catch (IOException e) {
                log.warn("Delete file failed: {}", e.getMessage());
            }
        }
        docRepository.deleteById(docId);
    }

    public CsKnowledgeDocEntity setActive(String docId, boolean active) {
        CsKnowledgeDocEntity doc = get(docId);
        doc.setIsActive(active);
        doc.setUpdatedAt(Instant.now());
        docRepository.save(doc);
        if (!active) {
            indexPipeline.removeVectors(docId);
        } else {
            return indexQuietly(docId);
        }
        return get(docId);
    }

    public CsKnowledgeDocEntity reindex(String docId) {
        return indexQuietly(docId);
    }

    public CsKnowledgeDocEntity upload(String originalFilename, byte[] bytes, String title, String category) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (bytes.length > knowledgeProperties.getMaxUploadBytes()) {
            throw new IllegalArgumentException("文件超过 10MB 限制");
        }
        String filename = originalFilename != null ? originalFilename : "upload.bin";
        String ext = extension(filename);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持 PDF / Word / Markdown / TXT / HTML");
        }

        String docId = IdGenerator.knowledgeDocId();
        Path dir = Path.of(knowledgeProperties.getUploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path dest = dir.resolve(docId + "_" + sanitize(filename));
            Files.write(dest, bytes);

            Instant now = Instant.now();
            String displayTitle = (title != null && !title.isBlank())
                    ? title.trim()
                    : stripExt(filename);
            CsKnowledgeDocEntity doc = CsKnowledgeDocEntity.builder()
                    .docId(docId)
                    .title(displayTitle)
                    .category(category)
                    .content("")
                    .source(filename)
                    .sourceType("FILE")
                    .fileName(filename)
                    .filePath(dest.toString())
                    .mimeType(mimeOf(ext))
                    .status("INDEXING")
                    .chunkCount(0)
                    .version(1)
                    .isActive(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            doc = docRepository.save(doc);
            return indexQuietly(docId);
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> toView(CsKnowledgeDocEntity doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", doc.getDocId());
        m.put("title", doc.getTitle());
        m.put("category", doc.getCategory());
        m.put("content", doc.getContent());
        m.put("source", doc.getSource());
        m.put("sourceType", doc.getSourceType());
        m.put("fileName", doc.getFileName());
        m.put("mimeType", doc.getMimeType());
        m.put("status", doc.getStatus());
        m.put("indexError", doc.getIndexError());
        m.put("chunkCount", doc.getChunkCount() != null ? doc.getChunkCount() : 0);
        m.put("tags", doc.getTags());
        m.put("version", doc.getVersion());
        m.put("isActive", Boolean.TRUE.equals(doc.getIsActive()));
        m.put("createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
        m.put("updatedAt", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : null);
        return m;
    }

    private CsKnowledgeDocEntity indexQuietly(String docId) {
        try {
            indexPipeline.reindex(docId);
        } catch (RuntimeException e) {
            log.warn("Index {} failed: {}", docId, e.getMessage());
        }
        return get(docId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String mimeOf(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "md", "markdown" -> "text/markdown";
            case "html", "htm" -> "text/html";
            default -> "text/plain";
        };
    }
}
