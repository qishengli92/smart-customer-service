package com.cs.common.model;

import com.cs.common.enums.MessageRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 聊天消息模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息唯一ID
     */
    private String messageId;

    /**
     * 所属会话ID
     */
    private String sessionId;

    /**
     * 消息角色
     */
    private MessageRole role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 产生此消息的 Agent 名称
     */
    private String agentName;

    /**
     * 工具调用名称（role=TOOL 时）
     */
    private String toolName;

    /**
     * 工具调用参数（role=TOOL 时）
     */
    private Map<String, Object> toolParams;

    /**
     * 时间戳
     */
    private Instant timestamp;

    /**
     * 额外元数据
     */
    private Map<String, Object> metadata;

    /**
     * 创建用户消息
     */
    public static ChatMessage userMsg(String sessionId, String content) {
        return ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(MessageRole.USER)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建助手消息
     */
    public static ChatMessage assistantMsg(String sessionId, String content, String agentName) {
        return ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(MessageRole.ASSISTANT)
                .content(content)
                .agentName(agentName)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 创建工具结果消息
     */
    public static ChatMessage toolMsg(String sessionId, String toolName, String content,
                                       Map<String, Object> params) {
        return ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(MessageRole.TOOL)
                .content(content)
                .toolName(toolName)
                .toolParams(params)
                .timestamp(Instant.now())
                .build();
    }
}
