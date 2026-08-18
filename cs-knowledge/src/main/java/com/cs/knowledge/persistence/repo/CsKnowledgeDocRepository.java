package com.cs.knowledge.persistence.repo;

import com.cs.knowledge.persistence.entity.CsKnowledgeDocEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CsKnowledgeDocRepository extends JpaRepository<CsKnowledgeDocEntity, String> {

    List<CsKnowledgeDocEntity> findAllByOrderByUpdatedAtDesc();

    @Query("SELECT d FROM CsKnowledgeDocEntity d WHERE d.chunkCount IS NULL OR d.chunkCount = 0")
    List<CsKnowledgeDocEntity> findUnindexed();

    @Query("""
            SELECT d FROM CsKnowledgeDocEntity d
            WHERE (:keyword IS NULL OR :keyword = ''
                OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(d.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(d.tags, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:category IS NULL OR :category = '' OR d.category = :category)
              AND (:status IS NULL OR :status = '' OR d.status = :status)
            ORDER BY d.updatedAt DESC
            """)
    List<CsKnowledgeDocEntity> search(@Param("keyword") String keyword,
                                      @Param("category") String category,
                                      @Param("status") String status);
}
