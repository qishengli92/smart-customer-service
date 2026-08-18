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
 * PG 表 {@code cs_knowledge_chunk}：切片元数据（向量在 Milvus，主键与 Milvus 对齐）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cs_knowledge_chunk")
public class CsKnowledgeChunkEntity {

    @Id
    @Column(name = "chunk_id", length = 64)
    private String chunkId;

    @Column(name = "doc_id", length = 64, nullable = false)
    private String docId;

    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    @Column(name = "heading", length = 512)
    private String heading;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at")
    private Instant createdAt;
}
