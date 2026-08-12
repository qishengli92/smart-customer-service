package com.cs.common.enums;

/**
 * 会话状态枚举
 */
public enum SessionStatus {

    ACTIVE("active", "活跃"),
    WAITING_CONFIRM("waiting_confirm", "等待用户确认写操作"),
    QUEUED("queued", "人工排队中"),
    HUMAN_ACTIVE("human_active", "人工服务中"),
    PAUSED("paused", "暂停"),
    CLOSED("closed", "已关闭");

    private final String code;
    private final String label;

    SessionStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
}
