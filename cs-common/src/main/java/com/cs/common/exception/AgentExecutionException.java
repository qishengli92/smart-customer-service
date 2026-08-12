package com.cs.common.exception;

/**
 * Agent 执行异常
 */
public class AgentExecutionException extends CustomerServiceException {

    public AgentExecutionException(String agentName, String message) {
        super("AGENT_ERROR", String.format("Agent [%s] 执行异常: %s", agentName, message));
    }

    public AgentExecutionException(String agentName, String message, Throwable cause) {
        super("AGENT_ERROR", String.format("Agent [%s] 执行异常: %s", agentName, message), cause);
    }
}
