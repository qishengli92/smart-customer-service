package com.cs.infra.agentscope;

import com.cs.infra.redis.RedisJsonStore;
import io.agentscope.core.state.AgentStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 记忆相关 Bean：短期 {@link AgentStateStore}（会话上下文自动存取）。
 * <p>
 * 长期记忆 {@code LongTermMemory} Bean 在 cs-memory 模块装配，由各 ReActAgent
 * {@code .longTermMemory(...).longTermMemoryMode(STATIC_CONTROL)} 挂载。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentScopeMemoryConfig {

    private final RedisJsonStore redisJsonStore;

    @Bean
    public AgentStateStore agentStateStore() {
        log.info("AgentScope AgentStateStore ready (Redis fallback to in-memory)");
        return new RedisAgentStateStore(redisJsonStore);
    }
}
