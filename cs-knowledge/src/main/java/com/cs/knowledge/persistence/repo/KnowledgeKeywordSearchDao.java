package com.cs.knowledge.persistence.repo;

import com.cs.knowledge.retrieval.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 关键词召回：GIN 倒排 {@code search_tsv @@ query}，{@code ts_rank_cd} 排序后 LIMIT。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class KnowledgeKeywordSearchDao {

    private static final String SEARCH_SQL = """
            WITH q AS (
                SELECT to_tsquery('simple', tsq) AS query
                FROM (SELECT nullif(cs_tsquery_or(:query), '') AS tsq) s
                WHERE tsq IS NOT NULL
            )
            SELECT
                c.chunk_id,
                c.doc_id,
                d.title,
                c.heading,
                c.content,
                d.category,
                ts_rank_cd(c.search_tsv, q.query, 32)::real AS keyword_score
            FROM q
            JOIN cs_knowledge_chunk c ON c.search_tsv @@ q.query
            JOIN cs_knowledge_doc d ON d.doc_id = c.doc_id
            WHERE d.is_active IS TRUE
              AND d.status = 'READY'
            ORDER BY keyword_score DESC, c.chunk_id ASC
            LIMIT :topK
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<KnowledgeChunk> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", normalized)
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
                normalized.substring(0, Math.min(30, normalized.length())),
                hits.size());
        return hits;
    }
}
