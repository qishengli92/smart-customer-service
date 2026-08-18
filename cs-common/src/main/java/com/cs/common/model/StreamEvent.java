package com.cs.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编排 → 前端 SSE 事件协议（token / agent_start / confirmation / error / done 等）。
 * <p>
 * 由 {@code SupervisorOrchestrator} 产出，{@code ChatController} 映射为 ServerSentEvent。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamEvent {

    /**
     * 事件类型：token / agent_start / agent_end / tool_call / tool_result /
     * confirmation / queue_update / human_message / done / error
     */
    private String type;

    /**
     * 事件数据
     */
    private String data;

    /**
     * 来源 Agent
     */
    private String agentName;

    /**
     * 关联会话（可选）
     */
    private String sessionId;

    /**
     * 创建 token 流式事件
     */
    public static StreamEvent token(String token, String agentName) {
        return StreamEvent.builder()
                .type("token")
                .data(token)
                .agentName(agentName)
                .build();
    }

    /**
     * 创建 Agent 开始事件
     */
    public static StreamEvent agentStart(String agentName) {
        return StreamEvent.builder()
                .type("agent_start")
                .data(agentName)
                .agentName(agentName)
                .build();
    }

    /**
     * 创建 Agent 结束事件
     */
    public static StreamEvent agentEnd(String agentName) {
        return StreamEvent.builder()
                .type("agent_end")
                .data(agentName)
                .agentName(agentName)
                .build();
    }

    /**
     * 创建工具调用事件
     */
    public static StreamEvent toolCall(String toolName, String agentName) {
        return StreamEvent.builder()
                .type("tool_call")
                .data(toolName)
                .agentName(agentName)
                .build();
    }

    /**
     * 创建工具结果事件
     */
    public static StreamEvent toolResult(String toolName, String result, String agentName) {
        return StreamEvent.builder()
                .type("tool_result")
                .data(toolName + ": " + result)
                .agentName(agentName)
                .build();
    }

    /**
     * 创建完成事件
     */
    public static StreamEvent done() {
        return done(null);
    }

    /**
     * 创建完成事件（附带 sessionId，供前端绑定会话）
     */
    public static StreamEvent done(String sessionId) {
        return StreamEvent.builder()
                .type("done")
                .data("[DONE]")
                .sessionId(sessionId)
                .build();
    }

    /**
     * 创建错误事件
     */
    public static StreamEvent error(String message) {
        return StreamEvent.builder()
                .type("error")
                .data(message)
                .build();
    }

    public static StreamEvent confirmation(String jsonPayload, String agentName) {
        return StreamEvent.builder()
                .type("confirmation")
                .data(jsonPayload)
                .agentName(agentName)
                .build();
    }

    public static StreamEvent queueUpdate(String jsonPayload) {
        return StreamEvent.builder()
                .type("queue_update")
                .data(jsonPayload)
                .build();
    }

    public static StreamEvent humanMessage(String message, String agentId) {
        return StreamEvent.builder()
                .type("human_message")
                .data(message)
                .agentName(agentId)
                .build();
    }

    /**
     * 知识库引用（JSON 数组：index/title/heading/category/score）。
     */
    public static StreamEvent citations(String jsonPayload, String agentName) {
        return StreamEvent.builder()
                .type("citations")
                .data(jsonPayload)
                .agentName(agentName)
                .build();
    }
}
