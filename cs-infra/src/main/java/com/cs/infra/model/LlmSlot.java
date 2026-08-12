package com.cs.infra.model;

import lombok.Builder;
import lombok.Data;

/**
 * 单次 Chat 调用的模型档位值对象（modelName / temperature / maxTokens）。
 */
@Data
@Builder
public class LlmSlot {

    private String name;

    @Builder.Default
    private Double temperature = 0.3;

    @Builder.Default
    private Integer maxTokens = 1024;

    public static LlmSlot of(String name, Double temperature, Integer maxTokens,
                             String fallbackName, double fallbackTemp, int fallbackMax) {
        return LlmSlot.builder()
                .name(name != null && !name.isBlank() ? name : fallbackName)
                .temperature(temperature != null ? temperature : fallbackTemp)
                .maxTokens(maxTokens != null ? maxTokens : fallbackMax)
                .build();
    }
}
