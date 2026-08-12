package com.cs.memory.hook;

import com.cs.memory.longterm.LongTermMemoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 长期记忆门面（兼容旧编排调用）。
 * <p>
 * <b>推荐路径</b>：各 ReActAgent 已挂载 AgentScope 原生 {@code LongTermMemory}
 * （{@link com.cs.memory.agentscope.MilvusLongTermMemory}），框架自动 retrieve/record。
 * 本类仅在非 Agent 路径需要手动读写时使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerMemoryHook {

    private final LongTermMemoryManager longTermMemory;

    /**
     * 手动加载长期记忆摘要（Agent 路径请依赖原生 LongTermMemory.retrieve）。
     */
    public String beforeReasoning(String userId, String userMsg) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        try {
            return longTermMemory.getUserProfileSummary(userId);
        } catch (Exception e) {
            log.warn("MemoryHook failed to load profile for user {}: {}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * 手动写入长期记忆（Agent 路径请依赖原生 LongTermMemory.record）。
     */
    public void afterResponse(String userId, String response, String agentName) {
        if (userId == null || userId.isBlank() || response == null || response.isBlank()) {
            return;
        }
        try {
            longTermMemory.store(userId, "interaction",
                    String.format("[%s] %s", agentName, response),
                    java.util.Map.of("agent", agentName != null ? agentName : ""));
        } catch (Exception e) {
            log.warn("MemoryHook failed to save for user {}: {}", userId, e.getMessage());
        }
    }
}
