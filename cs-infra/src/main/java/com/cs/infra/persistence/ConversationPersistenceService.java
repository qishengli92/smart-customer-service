package com.cs.infra.persistence;

import com.cs.common.enums.MessageRole;
import com.cs.common.enums.SessionStatus;
import com.cs.common.model.ChatMessage;
import com.cs.common.model.ChatSession;
import com.cs.common.model.HandoffRecord;
import com.cs.common.util.IdGenerator;
import com.cs.common.util.JsonUtils;
import com.cs.infra.persistence.entity.CsHandoffEntity;
import com.cs.infra.persistence.entity.CsMessageEntity;
import com.cs.infra.persistence.entity.CsSessionEntity;
import com.cs.infra.persistence.entity.CsToolCallLogEntity;
import com.cs.infra.persistence.entity.CsUserEntity;
import com.cs.infra.persistence.repo.CsHandoffRepository;
import com.cs.infra.persistence.repo.CsMessageRepository;
import com.cs.infra.persistence.repo.CsSessionRepository;
import com.cs.infra.persistence.repo.CsToolCallLogRepository;
import com.cs.infra.persistence.repo.CsUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 对话冷存储门面（PostgreSQL）：会话 / 消息 / 工具审计 / 交接单。
 * <p>
 * 阻塞 JDBC，编排器须在 {@code boundedElastic} 调用；热状态仍走 Redis/本地缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationPersistenceService {

    private final CsSessionRepository sessionRepository;
    private final CsMessageRepository messageRepository;
    private final CsToolCallLogRepository toolCallLogRepository;
    private final CsHandoffRepository handoffRepository;
    private final CsUserRepository userRepository;

    @Transactional
    public void upsertSession(ChatSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        try {
            CsSessionEntity entity = CsSessionEntity.builder()
                    .sessionId(session.getSessionId())
                    .tenantId(session.getTenantId() != null ? session.getTenantId() : "default")
                    .userId(session.getUserId() != null ? session.getUserId() : "anonymous")
                    .channel(session.getChannel())
                    .activeAgent(session.getActiveAgent())
                    .status(session.getStatus() != null ? session.getStatus().name() : "ACTIVE")
                    .context(session.getContext())
                    .createdAt(session.getCreatedAt() != null ? session.getCreatedAt() : Instant.now())
                    .lastActiveAt(session.getLastActiveAt() != null ? session.getLastActiveAt() : Instant.now())
                    .closedAt("CLOSED".equalsIgnoreCase(
                            session.getStatus() != null ? session.getStatus().name() : "")
                            ? Instant.now() : null)
                    .build();
            sessionRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist session {}: {}", session.getSessionId(), e.getMessage());
        }
    }

    @Transactional
    public void saveMessage(ChatMessage message) {
        if (message == null || message.getMessageId() == null) {
            return;
        }
        try {
            CsMessageEntity entity = CsMessageEntity.builder()
                    .messageId(message.getMessageId())
                    .sessionId(message.getSessionId())
                    .role(message.getRole() != null ? message.getRole().getCode() : "user")
                    .content(message.getContent() != null ? message.getContent() : "")
                    .agentName(message.getAgentName())
                    .toolName(message.getToolName())
                    .toolParams(message.getToolParams())
                    .metadata(message.getMetadata())
                    .createdAt(message.getTimestamp() != null ? message.getTimestamp() : Instant.now())
                    .build();
            messageRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist message {}: {}", message.getMessageId(), e.getMessage());
        }
    }

    @Transactional
    public void saveToolCallLog(String sessionId, String userId, String tenantId,
                                String toolName, String confirmationId, String idempotencyKey,
                                Map<String, Object> input, Object output, String status) {
        try {
            CsToolCallLogEntity entity = CsToolCallLogEntity.builder()
                    .id(IdGenerator.messageId())
                    .sessionId(sessionId)
                    .userId(userId)
                    .tenantId(tenantId != null ? tenantId : "default")
                    .toolName(toolName)
                    .confirmationId(confirmationId)
                    .idempotencyKey(idempotencyKey)
                    .inputParams(input)
                    .outputResult(output != null ? JsonUtils.toJson(output) : null)
                    .status(status)
                    .createdAt(Instant.now())
                    .build();
            toolCallLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist tool call log: {}", e.getMessage());
        }
    }

    @Transactional
    public void saveHandoff(HandoffRecord record) {
        if (record == null || record.getId() == null) {
            return;
        }
        try {
            CsHandoffEntity entity = CsHandoffEntity.builder()
                    .id(record.getId())
                    .sessionId(record.getSessionId())
                    .tenantId(record.getTenantId())
                    .userId(record.getUserId())
                    .reason(record.getReason())
                    .skillGroup(record.getSkillGroup())
                    .summary(record.getSummary())
                    .entities(record.getEntities())
                    .status(record.getStatus() != null ? record.getStatus().name() : "QUEUED")
                    .agentId(record.getAgentId())
                    .queuedAt(record.getQueuedAt())
                    .acceptedAt(record.getAcceptedAt())
                    .completedAt(record.getCompletedAt())
                    .build();
            handoffRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist handoff {}: {}", record.getId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<ChatSession> loadSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            return sessionRepository.findById(sessionId).map(this::toChatSession);
        } catch (Exception e) {
            log.warn("Failed to load session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<ChatSession> listSessionsByUser(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        int pageSize = Math.min(Math.max(limit, 1), 100);
        try {
            return sessionRepository
                    .findByUserIdOrderByLastActiveAtDesc(userId, PageRequest.of(0, pageSize))
                    .stream()
                    .map(this::toChatSession)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to list sessions for user {}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> listMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                    .map(this::toChatMessage)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to list messages for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public List<CsUserEntity> listActiveUsers() {
        try {
            return userRepository.findByStatusOrderByVipLevelDesc("active");
        } catch (Exception e) {
            log.warn("Failed to list users: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private ChatSession toChatSession(CsSessionEntity entity) {
        Map<String, Object> context = entity.getContext() != null
                ? new ConcurrentHashMap<>(entity.getContext())
                : new ConcurrentHashMap<>();
        return ChatSession.builder()
                .sessionId(entity.getSessionId())
                .tenantId(entity.getTenantId() != null ? entity.getTenantId() : "default")
                .userId(entity.getUserId())
                .channel(entity.getChannel())
                .activeAgent(entity.getActiveAgent())
                .status(SessionStatus.fromNameOrCode(entity.getStatus()))
                .context(context)
                .createdAt(entity.getCreatedAt())
                .lastActiveAt(entity.getLastActiveAt())
                .build();
    }

    private ChatMessage toChatMessage(CsMessageEntity entity) {
        return ChatMessage.builder()
                .messageId(entity.getMessageId())
                .sessionId(entity.getSessionId())
                .role(MessageRole.fromCode(entity.getRole()))
                .content(entity.getContent())
                .agentName(entity.getAgentName())
                .toolName(entity.getToolName())
                .toolParams(entity.getToolParams())
                .metadata(entity.getMetadata())
                .timestamp(entity.getCreatedAt())
                .build();
    }
}
