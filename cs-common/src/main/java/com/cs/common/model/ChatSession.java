package com.cs.common.model;

import com.cs.common.enums.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    /**
     * 会话唯一标识
     */
    private String sessionId;

    /**
     * 租户标识
     */
    @Builder.Default
    private String tenantId = "default";

    /**
     * 用户标识
     */
    private String userId;

    /**
     * 接入渠道（web/wecom/dingtalk）
     */
    private String channel;

    /**
     * 当前活跃 Agent 名称
     */
    private String activeAgent;

    /**
     * 会话状态
     */
    private SessionStatus status;

    /**
     * 创建时间
     */
    private Instant createdAt;

    /**
     * 最近活跃时间
     */
    private Instant lastActiveAt;

    /**
     * 会话上下文变量
     */
    @Builder.Default
    private Map<String, Object> context = new ConcurrentHashMap<>();

    /**
     * 更新活跃时间
     */
    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    /**
     * 设置上下文变量
     */
    public void setContextVar(String key, Object value) {
        this.context.put(key, value);
    }

    /**
     * 获取上下文变量
     */
    @SuppressWarnings("unchecked")
    public <T> T getContextVar(String key, Class<T> type) {
        Object value = this.context.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }
}
