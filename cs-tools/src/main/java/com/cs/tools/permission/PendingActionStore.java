package com.cs.tools.permission;

import com.cs.common.enums.PendingActionStatus;
import com.cs.common.model.PendingAction;
import com.cs.common.util.IdGenerator;
import com.cs.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PendingAction 存储：优先 Redis，失败时回退进程内 Map
 */
@Slf4j
@Component
public class PendingActionStore {

    private static final String KEY_PREFIX = "cs:pending:";
    private static final String SESSION_INDEX = "cs:pending:session:";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final Map<String, PendingAction> localStore = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIndex = new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Qualifier("reactiveStringRedisTemplate")
    private ReactiveRedisTemplate<String, String> reactiveRedis;

    public PendingAction create(String sessionId, String userId, String tenantId,
                                String toolName, Map<String, Object> arguments,
                                String permission, String argsSummary) {
        // 单 session 单 PENDING：已有则拒绝
        Optional<PendingAction> existing = findBySession(sessionId);
        if (existing.isPresent() && existing.get().getStatus() == PendingActionStatus.PENDING) {
            throw new IllegalStateException("当前会话已有待确认操作，请先确认或取消");
        }

        String confirmationId = IdGenerator.confirmationId();
        Instant now = Instant.now();
        PendingAction action = PendingAction.builder()
                .confirmationId(confirmationId)
                .sessionId(sessionId)
                .userId(userId)
                .tenantId(tenantId != null ? tenantId : "default")
                .toolName(toolName)
                .arguments(arguments)
                .idempotencyKey(confirmationId)
                .permission(permission)
                .argsSummary(argsSummary)
                .createdAt(now)
                .expiresAt(now.plus(TTL))
                .status(PendingActionStatus.PENDING)
                .build();

        save(action);
        log.info("PendingAction created: id={}, session={}, tool={}",
                confirmationId, sessionId, toolName);
        return action;
    }

    public void save(PendingAction action) {
        localStore.put(action.getConfirmationId(), action);
        if (action.getStatus() == PendingActionStatus.PENDING) {
            sessionIndex.put(action.getSessionId(), action.getConfirmationId());
        } else {
            sessionIndex.remove(action.getSessionId(), action.getConfirmationId());
        }
        if (reactiveRedis != null) {
            try {
                String json = JsonUtils.toJson(action);
                reactiveRedis.opsForValue()
                        .set(KEY_PREFIX + action.getConfirmationId(), json, TTL)
                        .block(Duration.ofSeconds(2));
                if (action.getStatus() == PendingActionStatus.PENDING) {
                    reactiveRedis.opsForValue()
                            .set(SESSION_INDEX + action.getSessionId(), action.getConfirmationId(), TTL)
                            .block(Duration.ofSeconds(2));
                } else {
                    reactiveRedis.delete(SESSION_INDEX + action.getSessionId())
                            .block(Duration.ofSeconds(2));
                }
            } catch (Exception e) {
                log.warn("Redis save PendingAction failed, using local: {}", e.getMessage());
            }
        }
    }

    public Optional<PendingAction> findById(String confirmationId) {
        PendingAction local = localStore.get(confirmationId);
        if (local != null) {
            return Optional.of(expireIfNeeded(local));
        }
        if (reactiveRedis != null) {
            try {
                String json = reactiveRedis.opsForValue()
                        .get(KEY_PREFIX + confirmationId)
                        .block(Duration.ofSeconds(2));
                if (json != null) {
                    PendingAction action = JsonUtils.fromJson(json, PendingAction.class);
                    localStore.put(confirmationId, action);
                    return Optional.of(expireIfNeeded(action));
                }
            } catch (Exception e) {
                log.warn("Redis get PendingAction failed: {}", e.getMessage());
            }
        }
        return Optional.empty();
    }

    public Optional<PendingAction> findBySession(String sessionId) {
        String id = sessionIndex.get(sessionId);
        if (id == null && reactiveRedis != null) {
            try {
                id = reactiveRedis.opsForValue()
                        .get(SESSION_INDEX + sessionId)
                        .block(Duration.ofSeconds(2));
            } catch (Exception e) {
                log.warn("Redis session index failed: {}", e.getMessage());
            }
        }
        if (id == null) {
            return Optional.empty();
        }
        return findById(id);
    }

    private PendingAction expireIfNeeded(PendingAction action) {
        if (action.getStatus() == PendingActionStatus.PENDING
                && action.getExpiresAt() != null
                && Instant.now().isAfter(action.getExpiresAt())) {
            action.setStatus(PendingActionStatus.EXPIRED);
            save(action);
        }
        return action;
    }
}
