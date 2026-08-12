package com.cs.infra.observability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LangFuse Trace 根：一次用户回合（含其子 Span/Generation）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceRecord {

    /**
     * Trace ID
     */
    private String traceId;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * Trace 名称
     */
    private String name;

    /**
     * 输入（用户消息）
     */
    private Map<String, Object> input;

    /**
     * 输出（最终回复）
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
     * 状态：ok / error
     */
    private String status;

    /**
     * 下属的所有 Span
     */
    @Builder.Default
    private List<TraceSpan> spans = new ArrayList<>();

    /**
     * 总 Token 用量
     */
    private TraceSpan.TokenUsage totalTokenUsage;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 添加 Span
     */
    public void addSpan(TraceSpan span) {
        this.spans.add(span);
    }

    /**
     * 计算总耗时
     */
    public long getDurationMs() {
        if (startTime != null && endTime != null) {
            return endTime.toEpochMilli() - startTime.toEpochMilli();
        }
        return -1;
    }
}
