package com.cs.order.repo;

import com.cs.order.entity.CsOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CsOrderRepository extends JpaRepository<CsOrderEntity, String> {

    Optional<CsOrderEntity> findByOrderNoIgnoreCase(String orderNo);

    @Query("""
            SELECT o FROM CsOrderEntity o
            WHERE UPPER(o.orderId) = UPPER(:key)
               OR UPPER(o.orderNo) = UPPER(:key)
            """)
    Optional<CsOrderEntity> findByOrderIdOrOrderNo(@Param("key") String key);
}
