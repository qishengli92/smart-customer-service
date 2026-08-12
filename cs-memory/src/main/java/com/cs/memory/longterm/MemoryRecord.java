package com.cs.memory.longterm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆记录 - 存储在 Milvus 中的长期记忆条目
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecord {

    /**
     * 记录ID
     */
    private String recordId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 记忆类型：preference / interaction / complaint / refund / profile
     */
    private String type;

    /**
     * 记忆内容
     */
    private String content;

    /**
     * 向量嵌入
     */
    private float[] embedding;

    /**
     * 元数据
     */
    private Map<String, String> metadata;

    /**
     * 创建时间
     */
    private Instant createdAt;

    /**
     * 相似度分数（检索结果中使用）
     */
    private Float score;
}
