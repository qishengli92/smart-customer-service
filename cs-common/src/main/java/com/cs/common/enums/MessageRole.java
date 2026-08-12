package com.cs.common.enums;

/**
 * 消息角色枚举
 */
public enum MessageRole {

    USER("user", "用户消息"),
    ASSISTANT("assistant", "助手回复"),
    SYSTEM("system", "系统消息"),
    TOOL("tool", "工具结果");

    private final String code;
    private final String label;

    MessageRole(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
}
