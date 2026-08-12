package com.cs.common.model;

import com.cs.common.enums.PendingActionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * HITL 写操作挂起确认单（confirmationId、工具名、参数摘要、过期时间、执行状态）。
 * <p>
 * 存于 PendingActionStore；用户确认后由 ConfirmedToolExecutor 幂等执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingAction {

    private String confirmationId;
    private String sessionId;
    private String userId;
    private String tenantId;
    private String toolName;
    private Map<String, Object> arguments;
    private String idempotencyKey;
    private String permission;
    private String argsSummary;
    private Instant createdAt;
    private Instant expiresAt;
    private PendingActionStatus status;
    private Object result;
}
