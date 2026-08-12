package com.cs.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Agent 切换审计（from→to / reason），编排器 sticky 与路由切换时可记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTransitionLog {

    private String sessionId;
    private String fromAgent;
    private String toAgent;
    /** STICKY_SKIP / RE_ROUTE / HANDOFF_INTERNAL / USER */
    private String trigger;
    private RoutingDecision routingSnapshot;
    private Instant at;
}
