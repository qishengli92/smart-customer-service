package com.cs.knowledge.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 检索结果：注入 Prompt 的编号上下文 + 结构化引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResult {

    private String context;
    private List<KnowledgeChunk> chunks;
    private List<KnowledgeCitation> citations;

    public static RagResult empty() {
        return RagResult.builder()
                .context("")
                .chunks(List.of())
                .citations(List.of())
                .build();
    }

    public boolean isEmpty() {
        return chunks == null || chunks.isEmpty();
    }
}
