package com.cs.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus 连接与 collection 配置（{@code cs.milvus}），供知识库 / 长期记忆共用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.milvus")
public class MilvusProperties {

    private String host = "localhost";

    private int port = 19530;

    private String database = "default";

    /**
     * Collection 名称前缀（多租户可拼接 tenantId）
     */
    private String collectionPrefix = "cs_";

    private int topK = 5;

    private double similarityThreshold = 0.7;

    /**
     * 向量维度（与 DashScope text-embedding-v3 对齐，默认 1024）
     */
    private int embeddingDimension = 1024;

    /**
     * 启动时是否自动将种子 FAQ 写入 Milvus
     */
    private boolean autoSeed = true;

    /** 兼容旧字段名 */
    public String getKnowledgeCollectionPrefix() {
        return collectionPrefix;
    }

    public String faqCollectionName() {
        return collectionPrefix + "faq";
    }

    /** 运营知识库向量 collection（解析分块后的文档） */
    public String knowledgeCollectionName() {
        return collectionPrefix + "knowledge";
    }

    /** 长期记忆向量 collection */
    public String memoryCollectionName() {
        return collectionPrefix + "memory";
    }
}
