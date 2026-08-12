package com.cs.agents.support;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.state.AgentStateStore;
import lombok.experimental.UtilityClass;

/**
 * AgentScope 记忆装配辅助：短期 {@link AgentStateStore} + 长期 {@link LongTermMemory}。
 */
@UtilityClass
@SuppressWarnings("deprecation")
public class AgentMemorySupport {

    /**
     * 挂载 2.0 会话态（短期）与原生 LongTermMemory（STATIC_CONTROL 自动 retrieve/record）。
     */
    public static ReActAgent.Builder applyMemory(
            ReActAgent.Builder builder,
            AgentStateStore stateStore,
            LongTermMemory longTermMemory) {
        return builder
                .stateStore(stateStore)
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                .longTermMemoryAsyncRecord(true);
    }

    /**
     * 构造按 Agent 隔离的 RuntimeContext，避免多领域 Agent 共用同一 session 槽位互相覆盖。
     * <p>
     * 槽位 key = {@code sessionId + "#" + agentName}；业务 sessionId 仍用于 Trace / 落库。
     */
    public static RuntimeContext runtimeContext(String sessionId, String userId, String agentName) {
        String slot = (sessionId != null ? sessionId : "anon")
                + "#"
                + (agentName != null ? agentName : "agent");
        return RuntimeContext.builder()
                .sessionId(slot)
                .userId(userId)
                .build();
    }
}
