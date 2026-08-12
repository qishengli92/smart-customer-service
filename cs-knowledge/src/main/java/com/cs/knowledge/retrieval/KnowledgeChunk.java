package com.cs.knowledge.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 知识检索结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    /**
     * 文档片段ID
     */
    private String chunkId;

    /**
     * 来源文档名称
     */
    private String sourceDoc;

    /**
     * 片段内容
     */
    private String content;

    /**
     * 相似度分数
     */
    private Float score;

    /**
     * 元数据
     */
    private Map<String, String> metadata;

    /**
     * 格式化为上下文注入文本
     */
    public String toContextText() {
        return String.format("[来源: %s]\n%s", sourceDoc, content);
    }
}
