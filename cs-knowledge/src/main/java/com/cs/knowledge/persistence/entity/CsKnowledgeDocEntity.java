package com.cs.knowledge.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * PG 表 {@code cs_knowledge_doc}：知识文档元数据与解析正文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cs_knowledge_doc")
public class CsKnowledgeDocEntity {

    @Id
    @Column(name = "doc_id", length = 64)
    private String docId;

    @Column(name = "title", length = 512, nullable = false)
    private String title;

    @Column(name = "category", length = 128)
    private String category;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "source", length = 256)
    private String source;

    /** ARTICLE / FAQ / FILE */
    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "parsed_text", columnDefinition = "text")
    private String parsedText;

    /** DRAFT / INDEXING / READY / FAILED */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "index_error", columnDefinition = "text")
    private String indexError;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "tags", length = 512)
    private String tags;

    @Column(name = "version")
    private Integer version;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
