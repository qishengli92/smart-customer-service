package com.cs.memory.hook;

import com.cs.memory.longterm.LongTermMemoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 记忆 Hook - 在 Agent 推理前后管理长期记忆的读写
 * <p>
 * 对应 AgentScope Java 的 MemoryHook 机制：
 * - beforeReasoning：从长期记忆检索相关信息注入上下文
 * - afterResponse：将本轮关键信息写入长期记忆
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerMemoryHook {

    private final LongTermMemoryManager longTermMemory;

    /**
     * 在推理前加载用户长期记忆
     *
     * @param userId   用户ID
     * @param userMsg  用户消息
     * @return 注入的上下文文本
     */
    public String beforeReasoning(String userId, String userMsg) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        try {
            String profile = longTermMemory.getUserProfileSummary(userId);
            log.debug("MemoryHook loaded profile for user: {}", userId);
            return profile;
        } catch (Exception e) {
            log.warn("MemoryHook failed to load profile for user {}: {}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * 在响应后保存关键信息到长期记忆
     *
     * @param userId   用户ID
     * @param response Agent 响应
     * @param agentName Agent 名称
     */
    public void afterResponse(String userId, String response, String agentName) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            longTermMemory.store(userId, "interaction",
                    String.format("[%s] %s", agentName, response),
                    java.util.Map.of("agent", agentName));
            log.debug("MemoryHook saved interaction for user: {}", userId);
        } catch (Exception e) {
            log.warn("MemoryHook failed to save for user {}: {}", userId, e.getMessage());
        }
    }
}
