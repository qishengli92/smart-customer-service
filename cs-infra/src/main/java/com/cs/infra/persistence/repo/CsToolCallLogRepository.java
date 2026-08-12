package com.cs.infra.persistence.repo;

import com.cs.infra.persistence.entity.CsToolCallLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CsToolCallLogRepository extends JpaRepository<CsToolCallLogEntity, String> {
}
