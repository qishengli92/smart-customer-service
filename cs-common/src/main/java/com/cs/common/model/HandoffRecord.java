package com.cs.common.model;

import com.cs.common.enums.HandoffStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 人工交接单领域模型：排队/接单/结单状态与技能组；热 Redis + 冷 PG 双写。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandoffRecord {

    private String id;
    private String sessionId;
    private String tenantId;
    private String userId;
    private String reason;
    private String skillGroup;
    private String summary;
    private Map<String, Object> entities;
    private HandoffStatus status;
    private String agentId;
    private Instant queuedAt;
    private Instant acceptedAt;
    private Instant completedAt;
}
