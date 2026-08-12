package com.cs.memory.hook;

import com.cs.memory.longterm.LongTermMemoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 应用层长期记忆：在 Agent 推理前后读写用户画像 / 交互摘要。
 * <p>
 * <b>与 AgentScope 的对应关系（概念对齐，非框架 API）：</b>
 * <ul>
 *   <li>行为类似 v1 的 {@code StaticLongTermMemoryHook}（STATIC_CONTROL：
 *       推理前 retrieve、回复后 record）</li>
 *   <li>存储侧类似 v1 的 {@code LongTermMemory}；会话短期态则对应
 *       {@code Memory} → 2.0 的 {@code AgentState} / {@code AgentStateStore}</li>
 *   <li>上述 LongTermMemory / Hook 在 AgentScope 2.0 已 {@code @Deprecated}，
 *       官方方向是改写为 {@code MiddlewareBase}（如 Mem0 middleware）；
 *       本类为应用层自管实现，不依赖废弃 API</li>
 *   <li>若日后框架化，用 {@code MiddlewareBase#onAgent}（前读后写）或
 *       {@code onSystemPrompt} 注入，勿再接 {@code StaticLongTermMemoryHook}</li>
 * </ul>
 * 由编排器显式调用，而非挂到 ReActAgent。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerMemoryHook {

    private final LongTermMemoryManager longTermMemory;

    /**
     * 推理前加载长期记忆（对齐 StaticLongTermMemoryHook 的 retrieve 时机）。
     *
     * @param userId   用户ID
     * @param userMsg  用户消息（预留按消息检索；当前实现主要用画像摘要）
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
     * 响应后写入长期记忆（对齐 StaticLongTermMemoryHook 的 record 时机）。
     *
     * @param userId    用户ID
     * @param response  Agent 响应
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
