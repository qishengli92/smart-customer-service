package com.cs.knowledge.persistence.repo;

import com.cs.knowledge.persistence.entity.CsKnowledgeChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CsKnowledgeChunkRepository extends JpaRepository<CsKnowledgeChunkEntity, String> {

    List<CsKnowledgeChunkEntity> findByDocIdOrderByOrdinalAsc(String docId);

    @Modifying
    void deleteByDocId(String docId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CsKnowledgeChunkEntity c SET c.title = :title, c.tags = :tags WHERE c.docId = :docId")
    int updateTitleAndTags(@Param("docId") String docId,
                           @Param("title") String title,
                           @Param("tags") String tags);
}
