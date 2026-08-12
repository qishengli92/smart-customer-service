package com.cs.memory.shortterm;

import com.cs.common.model.ChatMessage;
import com.cs.common.util.JsonUtils;
import com.cs.infra.redis.RedisJsonStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 会话内短期记忆：本地滑动窗口 + Redis 备份。
 * <p>
 * 概念对齐 AgentScope 会话 Memory / {@code AgentState} 上下文，应用层自管实现；
 * 编排器每轮读写，与长期记忆（Milvus）分离。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortTermMemoryManager {

    private static final int MAX_ROUNDS = 20;
    private static final String KEY_PREFIX = "cs:memory:short:";
    private static final Duration TTL = Duration.ofHours(2);

    private final Map<String, Deque<ChatMessage>> sessionHistories = new ConcurrentHashMap<>();
    private final RedisJsonStore redisJsonStore;

    public void addMessage(String sessionId, ChatMessage message) {
        Deque<ChatMessage> history = sessionHistories.computeIfAbsent(
                sessionId, k -> loadFromRedis(k));
        history.addLast(message);
        while (history.size() > MAX_ROUNDS * 2) {
            history.removeFirst();
        }
        persist(sessionId, history);
        log.trace("Message added to short-term memory: sessionId={}, role={}, size={}",
                sessionId, message.getRole(), history.size());
    }

    public List<ChatMessage> getHistory(String sessionId) {
        Deque<ChatMessage> history = sessionHistories.computeIfAbsent(
                sessionId, this::loadFromRedis);
        return history != null ? new ArrayList<>(history) : Collections.emptyList();
    }

    public String getRecentContext(String sessionId, int rounds) {
        List<ChatMessage> history = getHistory(sessionId);
        int startIndex = Math.max(0, history.size() - rounds * 2);
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            sb.append(msg.getRole().getCode()).append(": ")
                    .append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    public void clearHistory(String sessionId) {
        sessionHistories.remove(sessionId);
        redisJsonStore.delete(KEY_PREFIX + sessionId);
        log.debug("Short-term memory cleared: sessionId={}", sessionId);
    }

    public String generateSummary(String sessionId) {
        List<ChatMessage> history = getHistory(sessionId);
        if (history.isEmpty()) {
            return "";
        }
        int halfSize = history.size() / 2;
        StringBuilder sb = new StringBuilder("历史对话摘要：\n");
        for (int i = 0; i < halfSize; i++) {
            ChatMessage msg = history.get(i);
            sb.append("- ").append(msg.getRole().getCode()).append(": ")
                    .append(msg.getContent(), 0, Math.min(50, msg.getContent().length()))
                    .append("...\n");
        }
        return sb.toString();
    }

    private Deque<ChatMessage> loadFromRedis(String sessionId) {
        Deque<ChatMessage> deque = new ConcurrentLinkedDeque<>();
        redisJsonStore.getString(KEY_PREFIX + sessionId).ifPresent(json -> {
            try {
                ChatMessage[] arr = JsonUtils.fromJson(json, ChatMessage[].class);
                if (arr != null) {
                    for (ChatMessage m : arr) {
                        deque.addLast(m);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse short-term memory: {}", e.getMessage());
            }
        });
        return deque;
    }

    private void persist(String sessionId, Deque<ChatMessage> history) {
        redisJsonStore.setString(KEY_PREFIX + sessionId,
                JsonUtils.toJson(new ArrayList<>(history)), TTL);
    }
}
