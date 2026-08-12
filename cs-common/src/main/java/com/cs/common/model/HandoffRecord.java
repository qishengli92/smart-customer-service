package com.cs.common.model;

import com.cs.common.enums.HandoffStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 人工交接记录
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
