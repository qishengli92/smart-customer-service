package com.cs.gateway.controller;

import com.cs.common.model.ChatSession;
import com.cs.common.model.StreamEvent;
import com.cs.common.util.JsonUtils;
import com.cs.gateway.session.SessionManager;
import com.cs.orchestrator.SupervisorOrchestrator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Map;

/**
 * Web Chat SSE 流式控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final SupervisorOrchestrator orchestrator;
    private final SessionManager sessionManager;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false, defaultValue = "anonymous") String userId,
            @RequestParam(required = false, defaultValue = "default") String tenantId,
            @RequestParam String message) {

        log.info("Chat request: sessionId={}, userId={}, messageLength={}",
                sessionId, userId, message != null ? message.length() : 0);

        ChatSession session = sessionManager.getOrCreateSession(sessionId, userId, "web", tenantId);

        return Mono.defer(() -> Mono.just(session))
                .flatMapMany(s -> orchestrator.processMessage(s, message)
                        .doOnComplete(() -> sessionManager.save(s)))
                .contextWrite(Context.of(
                        "tenantId", tenantId != null ? tenantId : "default",
                        "userId", userId != null ? userId : "anonymous",
                        "sessionId", session.getSessionId()))
                .map(this::toSse);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChatPost(@RequestBody ChatRequest request) {
        return streamChat(request.getSessionId(), request.getUserId(),
                request.getTenantId() != null ? request.getTenantId() : "default",
                request.getMessage());
    }

    /**
     * 写操作确认 / 拒绝
     */
    @PostMapping(value = "/confirmations/{confirmationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> confirm(
            @PathVariable String confirmationId,
            @RequestBody ConfirmRequest body) {
        ChatSession session = sessionManager.getSession(body.getSessionId());
        if (session == null) {
            return Flux.just(toSse(StreamEvent.error("Session not found")));
        }
        String decision = body.getDecision() != null ? body.getDecision() : "REJECT";
        return orchestrator.processConfirmation(session, confirmationId, decision)
                .doOnComplete(() -> sessionManager.save(session))
                .contextWrite(Context.of(
                        "tenantId", session.getTenantId() != null ? session.getTenantId() : "default",
                        "userId", session.getUserId(),
                        "sessionId", session.getSessionId()))
                .map(this::toSse);
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, String> body) {
        String userId = body != null ? body.getOrDefault("userId", "anonymous") : "anonymous";
        String tenantId = body != null ? body.getOrDefault("tenantId", "default") : "default";
        ChatSession session = sessionManager.createSession(userId, "web", tenantId);
        return Map.of(
                "sessionId", session.getSessionId(),
                "userId", session.getUserId(),
                "tenantId", session.getTenantId(),
                "channel", session.getChannel(),
                "status", session.getStatus().name(),
                "createdAt", session.getCreatedAt().toString()
        );
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> getSession(@PathVariable String sessionId) {
        ChatSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            return Map.of("error", "Session not found", "sessionId", sessionId);
        }
        return Map.of(
                "sessionId", session.getSessionId(),
                "userId", session.getUserId(),
                "tenantId", session.getTenantId() != null ? session.getTenantId() : "default",
                "channel", session.getChannel(),
                "activeAgent", session.getActiveAgent() != null ? session.getActiveAgent() : "none",
                "status", session.getStatus().name(),
                "lastActiveAt", session.getLastActiveAt().toString()
        );
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> closeSession(@PathVariable String sessionId) {
        sessionManager.closeSession(sessionId);
        return Map.of("sessionId", sessionId, "status", "closed");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "activeSessions", sessionManager.getActiveSessionCount(),
                "version", "MVP-1.0"
        );
    }

    private ServerSentEvent<String> toSse(StreamEvent event) {
        return ServerSentEvent.<String>builder()
                .event(event.getType())
                .data(JsonUtils.toJson(event))
                .id(event.getAgentName() != null ? event.getAgentName() : "system")
                .build();
    }

    @Data
    public static class ChatRequest {
        private String sessionId;
        private String userId;
        private String tenantId;
        private String message;
    }

    @Data
    public static class ConfirmRequest {
        private String sessionId;
        private String decision;
    }
}
