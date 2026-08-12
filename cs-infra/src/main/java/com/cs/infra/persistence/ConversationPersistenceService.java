package com.cs.infra.persistence;

import com.cs.common.model.ChatMessage;
import com.cs.common.model.ChatSession;
import com.cs.common.model.HandoffRecord;
import com.cs.common.util.IdGenerator;
import com.cs.common.util.JsonUtils;
import com.cs.infra.persistence.entity.CsHandoffEntity;
import com.cs.infra.persistence.entity.CsMessageEntity;
import com.cs.infra.persistence.entity.CsSessionEntity;
import com.cs.infra.persistence.entity.CsToolCallLogEntity;
import com.cs.infra.persistence.repo.CsHandoffRepository;
import com.cs.infra.persistence.repo.CsMessageRepository;
import com.cs.infra.persistence.repo.CsSessionRepository;
import com.cs.infra.persistence.repo.CsToolCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

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
}
