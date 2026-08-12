package com.cs.infra.model;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 模型配置：API Key + 各档位（router/supervisor/expert/chitchat）参数解析。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LlmModelConfig {

    private final DashScopeModelProperties properties;

    public String getApiKey() {
        String key = properties.getApiKey();
        if (key == null || key.isBlank()) {
            key = System.getenv("DASHSCOPE_API_KEY");
        }
        if (key == null || key.isBlank()) {
            log.warn("DASHSCOPE_API_KEY 未配置，Agent 调用将失败");
        }
        return key;
    }

    public String getSupervisorModel() {
        return resolveSlot("supervisor").getName();
    }

    public String getExpertModel() {
        return resolveSlot("expert").getName();
    }

    public String getRouterModel() {
        return resolveSlot("router").getName();
    }

    public String getChitchatModel() {
        return resolveSlot("chitchat").getName();
    }

    /**
     * 解析模型档位；未知 key 回退到 expert。
     */
    public LlmSlot resolveSlot(String slotKey) {
        String key = slotKey == null ? "expert" : slotKey;
        DashScopeModelProperties.ModelSlot configured = properties.getModels() != null
                ? properties.getModels().get(key) : null;

        return switch (key) {
            case "router" -> LlmSlot.of(
                    configured != null ? configured.getName() : null,
                    configured != null ? configured.getTemperature() : null,
                    configured != null ? configured.getMaxTokens() : null,
                    "qwen3.7-plus", 0.1, 512);
            case "supervisor" -> LlmSlot.of(
                    configured != null ? configured.getName() : null,
                    configured != null ? configured.getTemperature() : null,
                    configured != null ? configured.getMaxTokens() : null,
                    "qwen3.7-plus", 0.3, 1024);
            case "chitchat" -> LlmSlot.of(
                    configured != null ? configured.getName() : null,
                    configured != null ? configured.getTemperature() : null,
                    configured != null ? configured.getMaxTokens() : null,
                    "qwen3.7-plus", 0.7, 1024);
            default -> LlmSlot.of(
                    configured != null ? configured.getName() : null,
                    configured != null ? configured.getTemperature() : null,
                    configured != null ? configured.getMaxTokens() : null,
                    "qwen3.7-plus", 0.5, 2048);
        };
    }
}
