package com.cs.common.enums;

/**
 * 会话状态机：ACTIVE → WAITING_CONFIRM / QUEUED / HUMAN_ACTIVE → CLOSED 等。
 * <p>
 * 由编排器与 HandoffController 共同推进，驱动 sticky 与确认续跑分支。
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
