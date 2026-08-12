package com.cs.infra.persistence.repo;

import com.cs.infra.persistence.entity.CsSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CsSessionRepository extends JpaRepository<CsSessionEntity, String> {
}
