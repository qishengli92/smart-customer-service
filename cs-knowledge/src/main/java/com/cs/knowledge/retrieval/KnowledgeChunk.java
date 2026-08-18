package com.cs.knowledge.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 单条检索命中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    private String chunkId;
    private String docId;
    private String sourceDoc;
    private String heading;
    private String content;
    private Float score;
    private Float vectorScore;
    private Float keywordScore;
    private Float rerankScore;
    private Map<String, String> metadata;

    public String toContextText() {
        String cat = metadata != null ? metadata.getOrDefault("category", "") : "";
        String title = sourceDoc != null ? sourceDoc : "";
        String head = heading != null && !heading.isBlank() && !heading.equals(title) ? heading : title;
        String label = cat.isBlank() ? head : head + " / " + cat;
        return String.format("[%s]\n%s", label, content);
    }
}
