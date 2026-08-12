package com.cs.gateway.controller;

import com.cs.knowledge.seed.KnowledgeIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识库运维入口：手动触发 FAQ 种子向量化写入 Milvus。
 * <p>
 * 与启动期 {@code KnowledgeSeedRunner} 互补；日常检索走 {@code KnowledgeRAGHook}。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class KnowledgeController {

    private final KnowledgeIngestService knowledgeIngestService;

    @PostMapping("/seed")
    public Map<String, Object> seedFaq() {
        int count = knowledgeIngestService.seedFaqToMilvus();
        return Map.of(
                "status", "ok",
                "upserted", count,
                "message", "FAQ seeds written to Milvus"
        );
    }
}
