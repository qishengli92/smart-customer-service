package com.cs.infra.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * DashScope 配置绑定（{@code cs.model.dashscope}，含嵌套 {@code models.*}）。
 * <p>
 * 原始配置源；业务侧优先经 {@link LlmModelConfig#resolveSlot} 取值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.model.dashscope")
public class DashScopeModelProperties {

    private String apiKey;

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private Map<String, ModelSlot> models = new HashMap<>();

    private String embeddingModel = "text-embedding-v3";

    @Data
    public static class ModelSlot {
        private String name;
        private Double temperature;
        private Integer maxTokens;
    }

    public String getSupervisorModel() {
        return modelName("supervisor", "qwen3.7-plus");
    }

    public String getExpertModel() {
        return modelName("expert", "qwen3.7-plus");
    }

    public String getRouterModel() {
        return modelName("router", "qwen3.7-plus");
    }

    public String getChitchatModel() {
        return modelName("chitchat", "qwen-turbo");
    }

    private String modelName(String slot, String fallback) {
        ModelSlot m = models.get(slot);
        if (m != null && m.getName() != null && !m.getName().isBlank()) {
            return m.getName();
        }
        return fallback;
    }
}
