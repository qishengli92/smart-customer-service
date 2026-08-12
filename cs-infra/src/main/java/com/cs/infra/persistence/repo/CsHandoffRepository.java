package com.cs.infra.persistence.repo;

import com.cs.infra.persistence.entity.CsHandoffEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CsHandoffRepository extends JpaRepository<CsHandoffEntity, String> {

    List<CsHandoffEntity> findByStatus(String status);
}
