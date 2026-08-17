package com.cs.gateway.controller;

import com.cs.common.enums.MessageRole;
import com.cs.common.model.ChatMessage;
import com.cs.common.model.ChatSession;
import com.cs.common.model.StreamEvent;
import com.cs.common.util.JsonUtils;
import com.cs.gateway.session.SessionManager;
import com.cs.infra.persistence.ConversationPersistenceService;
import com.cs.infra.persistence.entity.CsUserEntity;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Web Chat 唯一入口：会话解析 → {@link SupervisorOrchestrator} → SSE 推送。
 * <p>
 * 数据流：{@code StreamEvent} Flux 映射为 {@code ServerSentEvent}；
 * Reactor Context 注入 tenant/user/session（勿依赖 ThreadLocal 跨线程）。
 * 确认续跑：{@code POST /confirmations/{id}} 走编排器 CONFIRM 状态机，不重新路由。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final SupervisorOrchestrator orchestrator;
    private final SessionManager sessionManager;
    private final ConversationPersistenceService persistence;

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

    @GetMapping("/users")
    public List<Map<String, Object>> listUsers() {
        List<CsUserEntity> users = persistence.listActiveUsers();
        if (users.isEmpty()) {
            // 无种子数据时仍提供可切换的演示用户
            return List.of(
                    demoUser("U100001", "zhangsan", "张三", 3),
                    demoUser("U100002", "lisi", "李四", 2),
                    demoUser("U100003", "wangwu", "王五", 1),
                    demoUser("U100004", "zhaoliu", "赵六", 0),
                    demoUser("U100005", "testuser", "测试用户", 5)
            );
        }
        return users.stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getUserId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname() != null ? u.getNickname() : u.getUsername());
            m.put("vipLevel", u.getVipLevel() != null ? u.getVipLevel() : 0);
            return m;
        }).collect(Collectors.toList());
    }

    @GetMapping("/users/{userId}/sessions")
    public List<Map<String, Object>> listUserSessions(
            @PathVariable String userId,
            @RequestParam(required = false, defaultValue = "30") int limit) {
        return sessionManager.listSessionsByUser(userId, limit).stream()
                .map(this::sessionSummary)
                .collect(Collectors.toList());
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Map<String, Object> getSessionMessages(@PathVariable String sessionId) {
        ChatSession session = sessionManager.peekSession(sessionId);
        if (session == null) {
            return Map.of("error", "Session not found", "sessionId", sessionId);
        }
        List<Map<String, Object>> messages = sessionManager.listMessages(sessionId).stream()
                .filter(m -> m.getRole() == MessageRole.USER || m.getRole() == MessageRole.ASSISTANT)
                .map(this::messageView)
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", sessionSummary(session));
        result.put("messages", messages);
        return result;
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, String> body) {
        String userId = body != null ? body.getOrDefault("userId", "anonymous") : "anonymous";
        String tenantId = body != null ? body.getOrDefault("tenantId", "default") : "default";
        ChatSession session = sessionManager.createSession(userId, "web", tenantId);
        return sessionSummary(session);
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> getSession(@PathVariable String sessionId) {
        ChatSession session = sessionManager.peekSession(sessionId);
        if (session == null) {
            return Map.of("error", "Session not found", "sessionId", sessionId);
        }
        return sessionSummary(session);
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

    private Map<String, Object> sessionSummary(ChatSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", session.getSessionId());
        m.put("userId", session.getUserId());
        m.put("tenantId", session.getTenantId() != null ? session.getTenantId() : "default");
        m.put("channel", session.getChannel() != null ? session.getChannel() : "web");
        m.put("activeAgent", session.getActiveAgent() != null ? session.getActiveAgent() : "none");
        m.put("status", session.getStatus() != null ? session.getStatus().name() : "ACTIVE");
        m.put("createdAt", session.getCreatedAt() != null ? session.getCreatedAt().toString() : null);
        m.put("lastActiveAt", session.getLastActiveAt() != null ? session.getLastActiveAt().toString() : null);
        return m;
    }

    private Map<String, Object> messageView(ChatMessage message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messageId", message.getMessageId());
        m.put("role", message.getRole() != null ? message.getRole().getCode() : "user");
        m.put("content", message.getContent());
        m.put("agentName", message.getAgentName());
        m.put("timestamp", message.getTimestamp() != null ? message.getTimestamp().toString() : null);
        return m;
    }

    private static Map<String, Object> demoUser(String userId, String username, String nickname, int vip) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("username", username);
        m.put("nickname", nickname);
        m.put("vipLevel", vip);
        return m;
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
