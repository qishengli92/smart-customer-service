package com.cs.gateway.session;

import com.cs.common.enums.SessionStatus;
import com.cs.common.model.ChatSession;
import com.cs.common.util.IdGenerator;
import com.cs.infra.persistence.ConversationPersistenceService;
import com.cs.infra.redis.RedisJsonStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理：本地缓存 + Redis 热状态 + PostgreSQL 落库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    private static final String KEY_PREFIX = "cs:session:";
    private static final Duration TTL = Duration.ofHours(24);

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final RedisJsonStore redisJsonStore;
    private final ConversationPersistenceService persistence;

    public ChatSession createSession(String userId, String channel) {
        return createSession(userId, channel, "default");
    }

    public ChatSession createSession(String userId, String channel, String tenantId) {
        String sessionId = IdGenerator.sessionId();
        ChatSession session = ChatSession.builder()
                .sessionId(sessionId)
                .tenantId(tenantId != null ? tenantId : "default")
                .userId(userId != null ? userId : "anonymous")
                .channel(channel != null ? channel : "web")
                .status(SessionStatus.ACTIVE)
                .activeAgent(null)
                .createdAt(Instant.now())
                .lastActiveAt(Instant.now())
                .build();
        sessions.put(sessionId, session);
        persist(session);
        log.info("Session created: sessionId={}, userId={}, tenant={}",
                sessionId, session.getUserId(), session.getTenantId());
        return session;
    }

    public ChatSession getSession(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            session = redisJsonStore.get(KEY_PREFIX + sessionId, ChatSession.class).orElse(null);
            if (session != null) {
                sessions.put(sessionId, session);
            }
        }
        if (session != null) {
            session.touch();
            persist(session);
        }
        return session;
    }

    public ChatSession getOrCreateSession(String sessionId, String userId, String channel) {
        return getOrCreateSession(sessionId, userId, channel, "default");
    }

    public ChatSession getOrCreateSession(String sessionId, String userId, String channel, String tenantId) {
        if (sessionId != null) {
            ChatSession existing = getSession(sessionId);
            if (existing != null) {
                return existing;
            }
        }
        return createSession(userId, channel, tenantId);
    }

    public void save(ChatSession session) {
        sessions.put(session.getSessionId(), session);
        persist(session);
    }

    public void closeSession(String sessionId) {
        ChatSession session = getSession(sessionId);
        if (session != null) {
            session.setStatus(SessionStatus.CLOSED);
            persist(session);
            log.info("Session closed: sessionId={}", sessionId);
        }
    }

    public int getActiveSessionCount() {
        return (int) sessions.values().stream()
                .filter(s -> s.getStatus() == SessionStatus.ACTIVE
                        || s.getStatus() == SessionStatus.WAITING_CONFIRM
                        || s.getStatus() == SessionStatus.QUEUED
                        || s.getStatus() == SessionStatus.HUMAN_ACTIVE)
                .count();
    }

    private void persist(ChatSession session) {
        redisJsonStore.set(KEY_PREFIX + session.getSessionId(), session, TTL);
        persistence.upsertSession(session);
    }
}
