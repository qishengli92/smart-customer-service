package com.cs.knowledge.seed;

import com.cs.infra.config.MilvusProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时自动种子 FAQ 入 Milvus；失败仅告警，不阻断服务启动。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class KnowledgeSeedRunner implements ApplicationRunner {

    private final KnowledgeIngestService ingestService;
    private final MilvusProperties milvusProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!milvusProperties.isAutoSeed()) {
            log.info("cs.milvus.auto-seed=false, skip FAQ Milvus ingest");
            return;
        }
        try {
            int n = ingestService.seedFaqToMilvus();
            log.info("Startup knowledge seed finished, indexed={}", n);
        } catch (Exception e) {
            log.warn("Startup knowledge seed skipped/failed: {}", e.getMessage());
            log.debug("Milvus seed failure detail", e);
        }
    }
}
