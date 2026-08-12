package com.cs.infra.observability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * LangFuse Span 节点：一次 Agent 步骤或工具调用（可挂 TokenUsage）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceSpan {

    /**
     * Span ID
     */
    private String spanId;

    /**
     * 所属 Trace ID
     */
    private String traceId;

    /**
     * 父 Span ID
     */
    private String parentSpanId;

    /**
     * Span 类型：agent / tool / routing / handoff / generation
     */
    private String type;

    /**
     * Span 名称（Agent名或工具名）
     */
    private String name;

    /**
     * 大模型名称（generation 专用）
     */
    private String model;

    /**
     * 模型参数（temperature / max_tokens 等）
     */
    private Map<String, Object> modelParameters;

    /**
     * 输入数据
     */
    private Map<String, Object> input;

    /**
     * 输出数据
     */
    private Map<String, Object> output;

    /**
     * 开始时间
     */
    private Instant startTime;

    /**
     * 结束时间
     */
    private Instant endTime;

    /**
     * Token 用量
     */
    private TokenUsage tokenUsage;

    /**
     * 状态：ok / error
     */
    private String status;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 计算耗时（毫秒）
     */
    public long getDurationMs() {
        if (startTime != null && endTime != null) {
            return endTime.toEpochMilli() - startTime.toEpochMilli();
        }
        return -1;
    }

    /**
     * Token 用量
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenUsage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
}
