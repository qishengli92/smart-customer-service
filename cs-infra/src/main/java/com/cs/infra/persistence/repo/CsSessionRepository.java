package com.cs.infra.persistence.repo;

import com.cs.infra.persistence.entity.CsSessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CsSessionRepository extends JpaRepository<CsSessionEntity, String> {

    List<CsSessionEntity> findByUserIdOrderByLastActiveAtDesc(String userId, Pageable pageable);
}
