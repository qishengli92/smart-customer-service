package com.cs.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库解析 / 分块 / 检索 / 重排配置（{@code cs.knowledge}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.knowledge")
public class KnowledgeProperties {

    /**
     * 上传原文本地目录
     */
    private String uploadDir = "./data/knowledge";

    /**
     * 分块目标长度（汉字/字符）
     */
    private int chunkSize = 700;

    private int chunkOverlap = 100;

    /**
     * 向量召回条数（Rerank 前）
     */
    private int recallK = 20;

    private long maxUploadBytes = 10 * 1024 * 1024L;

    private Rerank rerank = new Rerank();

    @Data
    public static class Rerank {
        private boolean enabled = true;
        private String model = "gte-rerank-v2";
        private int topN = 5;
    }
}
