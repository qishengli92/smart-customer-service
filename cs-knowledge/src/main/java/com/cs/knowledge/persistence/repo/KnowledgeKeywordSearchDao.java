package com.cs.knowledge.persistence.repo;

import com.cs.knowledge.retrieval.KeywordQueryPrep;
import com.cs.knowledge.retrieval.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词召回：在 PG 内过滤 READY 文档、按字段权重打分、LIMIT TopK。
 * 候选过滤走 pg_trgm GIN（{@code lower(col) LIKE '%q%'}），不把全文装进 JVM。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class KnowledgeKeywordSearchDao {

    private static final String SEARCH_SQL = """
            WITH params AS (
                SELECT
                    :query AS q,
                    ('%' || replace(replace(replace(:query, '!', '!!'), '%', '!%'), '_', '!_') || '%') AS q_like,
                    COALESCE(string_to_array(nullif(:tokens, ''), chr(31)), ARRAY[]::text[]) AS toks
            )
            SELECT
                c.chunk_id,
                c.doc_id,
                d.title,
                c.heading,
                c.content,
                d.category,
                LEAST(1.0,
                    CASE
                        WHEN length(d.title) > 0
                         AND (strpos(lower(d.title), p.q) > 0 OR strpos(p.q, lower(d.title)) > 0)
                        THEN 0.5 ELSE 0
                    END
                    + CASE
                        WHEN length(coalesce(c.heading, '')) > 0
                         AND strpos(lower(c.heading), p.q) > 0
                        THEN 0.25 ELSE 0
                    END
                    + COALESCE((
                        SELECT SUM(
                            CASE WHEN strpos(lower(d.title), tok) > 0
                                   OR strpos(lower(coalesce(c.heading, '')), tok) > 0
                                 THEN 0.2 ELSE 0 END
                            + CASE WHEN strpos(lower(c.content), tok) > 0 THEN 0.08 ELSE 0 END
                            + CASE WHEN strpos(lower(coalesce(d.tags, '')), tok) > 0 THEN 0.15 ELSE 0 END
                        )
                        FROM unnest(p.toks) AS tok
                        WHERE length(tok) >= 2
                    ), 0)
                )::real AS keyword_score
            FROM cs_knowledge_chunk c
            INNER JOIN cs_knowledge_doc d ON d.doc_id = c.doc_id
            CROSS JOIN params p
            WHERE d.is_active IS TRUE
              AND d.status = 'READY'
              AND (
                    (length(d.title) > 0 AND strpos(p.q, lower(d.title)) > 0)
                 OR lower(d.title) LIKE p.q_like ESCAPE '!'
                 OR lower(coalesce(c.heading, '')) LIKE p.q_like ESCAPE '!'
                 OR lower(c.content) LIKE p.q_like ESCAPE '!'
                 OR lower(coalesce(d.tags, '')) LIKE p.q_like ESCAPE '!'
                 OR EXISTS (
                        SELECT 1
                        FROM unnest(p.toks) AS tok
                        WHERE length(tok) >= 2
                          AND (
                                lower(d.title) LIKE ('%' || replace(replace(replace(tok, '!', '!!'), '%', '!%'), '_', '!_') || '%') ESCAPE '!'
                             OR lower(coalesce(c.heading, '')) LIKE ('%' || replace(replace(replace(tok, '!', '!!'), '%', '!%'), '_', '!_') || '%') ESCAPE '!'
                             OR lower(c.content) LIKE ('%' || replace(replace(replace(tok, '!', '!!'), '%', '!%'), '_', '!_') || '%') ESCAPE '!'
                             OR lower(coalesce(d.tags, '')) LIKE ('%' || replace(replace(replace(tok, '!', '!!'), '%', '!%'), '_', '!_') || '%') ESCAPE '!'
                          )
                    )
              )
            ORDER BY keyword_score DESC, c.chunk_id ASC
            LIMIT :topK
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<KnowledgeChunk> search(String query, int topK) {
        KeywordQueryPrep.Prepared prepared = KeywordQueryPrep.prepare(query);
        if (prepared.query().isBlank() || topK <= 0) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", prepared.query())
                .addValue("tokens", prepared.tokens())
                .addValue("topK", topK);
        List<KnowledgeChunk> hits = jdbcTemplate.query(SEARCH_SQL, params, (rs, rowNum) -> {
            Map<String, String> meta = new HashMap<>();
            String category = rs.getString("category");
            if (category != null && !category.isBlank()) {
                meta.put("category", category);
            }
            float score = rs.getFloat("keyword_score");
            return KnowledgeChunk.builder()
                    .chunkId(rs.getString("chunk_id"))
                    .docId(rs.getString("doc_id"))
                    .sourceDoc(rs.getString("title"))
                    .heading(rs.getString("heading"))
                    .content(rs.getString("content"))
                    .keywordScore(score)
                    .score(score)
                    .metadata(meta)
                    .build();
        });
        log.debug("PG keyword search: query={}, hits={}",
                prepared.query().substring(0, Math.min(30, prepared.query().length())),
                hits.size());
        return hits;
    }
}
