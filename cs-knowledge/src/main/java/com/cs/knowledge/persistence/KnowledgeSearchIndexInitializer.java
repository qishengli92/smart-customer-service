package com.cs.knowledge.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Statement;

/**
 * 已有库（Hibernate ddl-auto、非首次 Docker volume）补齐 pg_trgm 与关键词 GIN 索引。
 * 使用 {@code CREATE INDEX CONCURRENTLY}，避免启动时锁表。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class KnowledgeSearchIndexInitializer implements ApplicationRunner {

    private static final String[] INDEX_DDL = {
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_knowledge_doc_ready
                ON cs_knowledge_doc(doc_id)
                WHERE is_active IS TRUE AND status = 'READY'
            """,
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_knowledge_chunk_content_trgm
                ON cs_knowledge_chunk USING gin (lower(content) gin_trgm_ops)
            """,
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_knowledge_chunk_heading_trgm
                ON cs_knowledge_chunk USING gin (lower(heading) gin_trgm_ops)
            """,
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_knowledge_doc_title_trgm
                ON cs_knowledge_doc USING gin (lower(title) gin_trgm_ops)
            """,
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_knowledge_doc_tags_trgm
                ON cs_knowledge_doc USING gin (lower(tags) gin_trgm_ops)
            """
    };

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        } catch (Exception e) {
            log.warn("pg_trgm not available; keyword search still runs but may sequential-scan: {}",
                    e.getMessage());
            return;
        }
        for (String ddl : INDEX_DDL) {
            try {
                executeOutsideTransaction(ddl);
            } catch (Exception e) {
                log.warn("Knowledge search index skipped: {}", e.getMessage());
            }
        }
        log.info("Knowledge keyword search indexes ready (pg_trgm)");
    }

    private void executeOutsideTransaction(String ddl) {
        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
            boolean prev = con.getAutoCommit();
            con.setAutoCommit(true);
            try (Statement st = con.createStatement()) {
                st.execute(ddl);
            } finally {
                con.setAutoCommit(prev);
            }
            return null;
        });
    }
}
