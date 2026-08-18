package com.cs.knowledge.persistence.repo;

import com.cs.knowledge.persistence.entity.CsKnowledgeChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CsKnowledgeChunkRepository extends JpaRepository<CsKnowledgeChunkEntity, String> {

    List<CsKnowledgeChunkEntity> findByDocIdOrderByOrdinalAsc(String docId);

    @Modifying
    void deleteByDocId(String docId);

    @Query("""
            SELECT c FROM CsKnowledgeChunkEntity c
            WHERE c.docId IN (
                SELECT d.docId FROM CsKnowledgeDocEntity d
                WHERE d.isActive = TRUE AND d.status = 'READY'
            )
            """)
    List<CsKnowledgeChunkEntity> findAllSearchable();
}
