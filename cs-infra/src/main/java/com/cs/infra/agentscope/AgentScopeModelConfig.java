package com.cs.infra.agentscope;

import com.cs.infra.model.LlmModelConfig;
import com.cs.infra.model.LlmSlot;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope Java 2.0 模型 Bean 装配：构建 {@link DashScopeChatModel}。
 * <p>
 * 档位：{@code chitchatChatModel} / {@code expertChatModel} / {@code routerChatModel}，
 * 参数来自 {@link LlmModelConfig}；领域 Agent 通过 {@code @Qualifier} 注入。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentScopeModelConfig {

    private final LlmModelConfig llmModelConfig;

    @Bean(name = "chitchatChatModel")
    public DashScopeChatModel chitchatChatModel() {
        return buildModel(llmModelConfig.resolveSlot("chitchat"));
    }

    @Bean(name = "expertChatModel")
    public DashScopeChatModel expertChatModel() {
        return buildModel(llmModelConfig.resolveSlot("expert"));
    }

    @Bean(name = "routerChatModel")
    public DashScopeChatModel routerChatModel() {
        return buildModel(llmModelConfig.resolveSlot("router"));
    }

    private DashScopeChatModel buildModel(LlmSlot slot) {
        String apiKey = llmModelConfig.getApiKey();
        GenerateOptions options = GenerateOptions.builder()
                .temperature(slot.getTemperature())
                .maxTokens(slot.getMaxTokens())
                .build();

        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(slot.getName())
                .stream(false)
                .defaultOptions(options);

        log.info("AgentScope DashScopeChatModel ready: model={}, temp={}, maxTokens={}",
                slot.getName(), slot.getTemperature(), slot.getMaxTokens());
        return builder.build();
    }
}
