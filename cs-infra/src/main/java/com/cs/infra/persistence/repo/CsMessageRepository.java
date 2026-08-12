package com.cs.infra.persistence.repo;

import com.cs.infra.persistence.entity.CsMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CsMessageRepository extends JpaRepository<CsMessageEntity, String> {

    List<CsMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
