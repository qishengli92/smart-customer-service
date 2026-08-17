package com.cs.infra.persistence.repo;

import com.cs.infra.persistence.entity.CsUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CsUserRepository extends JpaRepository<CsUserEntity, String> {

    List<CsUserEntity> findByStatusOrderByVipLevelDesc(String status);
}
