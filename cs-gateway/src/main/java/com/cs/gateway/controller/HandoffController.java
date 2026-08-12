package com.cs.gateway.controller;

import com.cs.common.enums.SessionStatus;
import com.cs.common.model.ChatSession;
import com.cs.common.model.HandoffRecord;
import com.cs.gateway.session.SessionManager;
import com.cs.tools.handoff.HumanHandoffTool;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 人工交接坐席 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/handoff")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class HandoffController {

    private final HumanHandoffTool humanHandoffTool;
    private final SessionManager sessionManager;

    @GetMapping("/queue")
    public List<HandoffRecord> listQueue() {
        return humanHandoffTool.listQueued();
    }

    @PostMapping("/{id}/accept")
    public Map<String, Object> accept(@PathVariable String id, @RequestBody AcceptRequest body) {
        HandoffRecord record = humanHandoffTool.accept(id, body.getAgentId());
        ChatSession session = sessionManager.getSession(record.getSessionId());
        if (session != null) {
            session.setStatus(SessionStatus.HUMAN_ACTIVE);
            sessionManager.save(session);
        }
        return Map.of(
                "handoffId", record.getId(),
                "status", record.getStatus().name(),
                "agentId", record.getAgentId(),
                "sessionId", record.getSessionId()
        );
    }

    @PostMapping("/{id}/message")
    public Map<String, Object> message(@PathVariable String id, @RequestBody SeatMessage body) {
        HandoffRecord record = humanHandoffTool.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("交接单不存在"));
        log.info("Seat message: handoff={}, agent={}, msg={}", id, body.getAgentId(), body.getMessage());
        return Map.of(
                "handoffId", id,
                "sessionId", record.getSessionId(),
                "delivered", true,
                "message", body.getMessage() != null ? body.getMessage() : ""
        );
    }

    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@PathVariable String id,
                                        @RequestBody(required = false) CompleteRequest body) {
        HandoffRecord record = humanHandoffTool.complete(id);
        ChatSession session = sessionManager.getSession(record.getSessionId());
        boolean backToAi = body == null || body.isReturnToAi();
        if (session != null) {
            session.setStatus(backToAi ? SessionStatus.ACTIVE : SessionStatus.CLOSED);
            if (backToAi) {
                session.setActiveAgent(null);
            }
            sessionManager.save(session);
        }
        return Map.of(
                "handoffId", record.getId(),
                "status", record.getStatus().name(),
                "sessionStatus", session != null ? session.getStatus().name() : "unknown"
        );
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        HandoffRecord record = humanHandoffTool.cancel(id);
        ChatSession session = sessionManager.getSession(record.getSessionId());
        if (session != null) {
            session.setStatus(SessionStatus.ACTIVE);
            sessionManager.save(session);
        }
        return Map.of("handoffId", id, "status", record.getStatus().name());
    }

    @Data
    public static class AcceptRequest {
        private String agentId;
    }

    @Data
    public static class SeatMessage {
        private String agentId;
        private String message;
    }

    @Data
    public static class CompleteRequest {
        private boolean returnToAi = true;
    }
}
