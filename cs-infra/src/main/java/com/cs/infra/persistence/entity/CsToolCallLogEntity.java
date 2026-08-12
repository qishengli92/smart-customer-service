package com.cs.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cs_tool_call_log")
public class CsToolCallLogEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "tool_name", length = 128, nullable = false)
    private String toolName;

    @Column(name = "confirmation_id", length = 64)
    private String confirmationId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_params", columnDefinition = "jsonb")
    private Map<String, Object> inputParams;

    @Column(name = "output_result", columnDefinition = "text")
    private String outputResult;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;
}
