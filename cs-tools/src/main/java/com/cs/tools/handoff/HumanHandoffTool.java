package com.cs.tools.handoff;

import com.cs.common.enums.HandoffStatus;
import com.cs.common.model.HandoffRecord;
import com.cs.common.util.IdGenerator;
import com.cs.common.util.JsonUtils;
import com.cs.infra.persistence.ConversationPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人工转接工具兼交接单存储（Redis 热队列 + PostgreSQL 审计）。
 * <p>
 * 供 {@code HumanCollabAgent} / 编排器入队，{@code HandoffController} 坐席接单结单。
 */
@Slf4j
@Component
public class HumanHandoffTool {

    private static final String KEY_PREFIX = "cs:handoff:";
    private static final String QUEUE_KEY = "cs:handoff:queue";

    private final Map<String, HandoffRecord> localStore = new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Qualifier("reactiveStringRedisTemplate")
    private ReactiveRedisTemplate<String, String> reactiveRedis;

    @Autowired(required = false)
    private ConversationPersistenceService persistence;

    public HandoffRecord enqueue(String sessionId, String tenantId, String userId,
                                 String reason, String urgency, String summary,
                                 Map<String, Object> entities) {
        String skillGroup = mapSkillGroup(reason, urgency);
        HandoffRecord record = HandoffRecord.builder()
                .id(IdGenerator.handoffId())
                .sessionId(sessionId)
                .tenantId(tenantId != null ? tenantId : "default")
                .userId(userId)
                .reason(reason)
                .skillGroup(skillGroup)
                .summary(summary)
                .entities(entities)
                .status(HandoffStatus.QUEUED)
                .queuedAt(Instant.now())
                .build();
        save(record);
        log.info("Handoff queued: id={}, session={}, group={}", record.getId(), sessionId, skillGroup);
        return record;
    }

    public String transferToHuman(String reason, String urgency, String summary) {
        log.info("Human handoff requested: reason={}, urgency={}", reason, urgency);
        return String.format("已为您转接人工客服。原因：%s，紧急程度：%s。对话摘要已发送给客服，请稍等。",
                reason, urgency);
    }

    public Optional<HandoffRecord> findById(String id) {
        HandoffRecord local = localStore.get(id);
        if (local != null) {
            return Optional.of(local);
        }
        if (reactiveRedis != null) {
            try {
                String json = reactiveRedis.opsForValue().get(KEY_PREFIX + id).block(Duration.ofSeconds(2));
                if (json != null) {
                    HandoffRecord r = JsonUtils.fromJson(json, HandoffRecord.class);
                    localStore.put(id, r);
                    return Optional.of(r);
                }
            } catch (Exception e) {
                log.warn("Redis get handoff failed: {}", e.getMessage());
            }
        }
        return Optional.empty();
    }

    public List<HandoffRecord> listQueued() {
        List<HandoffRecord> list = new ArrayList<>();
        for (HandoffRecord r : localStore.values()) {
            if (r.getStatus() == HandoffStatus.QUEUED) {
                list.add(r);
            }
        }
        return list;
    }

    public HandoffRecord accept(String id, String agentId) {
        HandoffRecord record = findById(id).orElseThrow(() -> new IllegalArgumentException("交接单不存在"));
        if (record.getStatus() != HandoffStatus.QUEUED) {
            throw new IllegalStateException("交接单状态不可领取: " + record.getStatus());
        }
        record.setStatus(HandoffStatus.ACTIVE);
        record.setAgentId(agentId);
        record.setAcceptedAt(Instant.now());
        save(record);
        return record;
    }

    public HandoffRecord complete(String id) {
        HandoffRecord record = findById(id).orElseThrow(() -> new IllegalArgumentException("交接单不存在"));
        record.setStatus(HandoffStatus.DONE);
        record.setCompletedAt(Instant.now());
        save(record);
        return record;
    }

    public HandoffRecord cancel(String id) {
        HandoffRecord record = findById(id).orElseThrow(() -> new IllegalArgumentException("交接单不存在"));
        record.setStatus(HandoffStatus.CANCELLED);
        record.setCompletedAt(Instant.now());
        save(record);
        return record;
    }

    private void save(HandoffRecord record) {
        localStore.put(record.getId(), record);
        if (reactiveRedis != null) {
            try {
                reactiveRedis.opsForValue()
                        .set(KEY_PREFIX + record.getId(), JsonUtils.toJson(record), Duration.ofHours(24))
                        .block(Duration.ofSeconds(2));
                if (record.getStatus() == HandoffStatus.QUEUED) {
                    reactiveRedis.opsForList()
                            .rightPush(QUEUE_KEY, record.getId())
                            .block(Duration.ofSeconds(2));
                }
            } catch (Exception e) {
                log.warn("Redis save handoff failed: {}", e.getMessage());
            }
        }
        if (persistence != null) {
            persistence.saveHandoff(record);
        }
    }

    private String mapSkillGroup(String reason, String urgency) {
        if (reason != null && reason.contains("投诉")) {
            return "complaint";
        }
        if ("HIGH".equalsIgnoreCase(urgency) || "URGENT".equalsIgnoreCase(urgency)) {
            return "priority";
        }
        return "general";
    }
}
