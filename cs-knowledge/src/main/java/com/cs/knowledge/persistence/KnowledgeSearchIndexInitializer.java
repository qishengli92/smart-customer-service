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
 * 已有库补齐关键词倒排：lexemes 函数、search_tsv、GIN。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class KnowledgeSearchIndexInitializer implements ApplicationRunner {

    private static final String[] SETUP_DDL = {
            """
            CREATE OR REPLACE FUNCTION cs_search_lexemes(t text)
            RETURNS text[]
            LANGUAGE plpgsql
            IMMUTABLE
            AS $$
            DECLARE
                s text;
                i int := 1;
                n int;
                ch text;
                nxt text;
                buf text := '';
                acc text[] := ARRAY[]::text[];
            BEGIN
                IF t IS NULL OR btrim(t) = '' THEN
                    RETURN acc;
                END IF;
                s := lower(t);
                n := char_length(s);
                WHILE i <= n LOOP
                    ch := substr(s, i, 1);
                    IF ch ~ '[a-z0-9]' THEN
                        buf := buf || ch;
                    ELSE
                        IF char_length(buf) >= 2 THEN
                            acc := acc || buf;
                        END IF;
                        buf := '';
                        -- CJK Unified Ideographs U+4E00..U+9FFF (no CJK literals in source)
                        IF ascii(ch) BETWEEN 19968 AND 40959 THEN
                            nxt := CASE WHEN i < n THEN substr(s, i + 1, 1) ELSE '' END;
                            IF ascii(nxt) BETWEEN 19968 AND 40959 THEN
                                acc := acc || (ch || nxt);
                            ELSIF i = 1 OR ascii(substr(s, i - 1, 1)) NOT BETWEEN 19968 AND 40959 THEN
                                acc := acc || ch;
                            END IF;
                        END IF;
                    END IF;
                    i := i + 1;
                END LOOP;
                IF char_length(buf) >= 2 THEN
                    acc := acc || buf;
                END IF;
                RETURN acc;
            END;
            $$
            """,
            """
            CREATE OR REPLACE FUNCTION cs_tsquery_or(t text)
            RETURNS text
            LANGUAGE sql
            IMMUTABLE
            AS $$
                SELECT coalesce(string_agg(lexeme, ' | '), '')
                FROM (SELECT DISTINCT unnest(cs_search_lexemes(t)) AS lexeme) s
                WHERE lexeme <> '';
            $$
            """,
            "ALTER TABLE cs_knowledge_chunk ADD COLUMN IF NOT EXISTS title VARCHAR(512)",
            "ALTER TABLE cs_knowledge_chunk ADD COLUMN IF NOT EXISTS tags VARCHAR(512)",
            """
            UPDATE cs_knowledge_chunk c
            SET title = d.title, tags = d.tags
            FROM cs_knowledge_doc d
            WHERE c.doc_id = d.doc_id
              AND (c.title IS DISTINCT FROM d.title OR c.tags IS DISTINCT FROM d.tags)
            """,
            """
            DO $$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'cs_knowledge_chunk'
                      AND column_name = 'search_tsv'
                ) THEN
                    ALTER TABLE cs_knowledge_chunk ADD COLUMN search_tsv tsvector
                    GENERATED ALWAYS AS (
                        setweight(to_tsvector('simple', array_to_string(cs_search_lexemes(coalesce(title, '')), ' ')), 'A')
                        || setweight(to_tsvector('simple', array_to_string(cs_search_lexemes(
                            trim(both from coalesce(heading, '') || ' ' || coalesce(tags, ''))), ' ')), 'B')
                        || setweight(to_tsvector('simple', array_to_string(cs_search_lexemes(content), ' ')), 'C')
                    ) STORED;
                END IF;
            END $$
            """
    };

    private static final String[] CONCURRENT_DDL = {
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_knowledge_doc_ready
                ON cs_knowledge_doc(doc_id)
                WHERE is_active IS TRUE AND status = 'READY'
            """,
            """
            CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_knowledge_chunk_tsv
                ON cs_knowledge_chunk USING gin (search_tsv)
            """,
            "DROP INDEX CONCURRENTLY IF EXISTS idx_knowledge_chunk_content_trgm",
            "DROP INDEX CONCURRENTLY IF EXISTS idx_knowledge_chunk_heading_trgm",
            "DROP INDEX CONCURRENTLY IF EXISTS idx_knowledge_doc_title_trgm",
            "DROP INDEX CONCURRENTLY IF EXISTS idx_knowledge_doc_tags_trgm"
    };

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        for (String ddl : SETUP_DDL) {
            try {
                jdbcTemplate.execute(ddl);
            } catch (Exception e) {
                log.warn("Knowledge FTS setup skipped: {}", e.getMessage());
            }
        }
        for (String ddl : CONCURRENT_DDL) {
            try {
                executeOutsideTransaction(ddl);
            } catch (Exception e) {
                log.warn("Knowledge FTS index skipped: {}", e.getMessage());
            }
        }
        log.info("Knowledge keyword search ready (tsvector GIN)");
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
