package com.cs.common.enums;

/**
 * 用户意图枚举 → 领域 Agent 派发键（code 写入 session.activeAgent）。
 * <p>
 * MVP 路由裁剪见 {@code RouterAgent}：PRE_SALES→KNOWLEDGE，COMPLAINT→HUMAN_SERVICE。
 */
public enum IntentType {

    PRE_SALES("pre_sales", "售前咨询"),
    ORDER("order", "订单服务"),
    AFTER_SALES("after_sales", "售后支持"),
    COMPLAINT("complaint", "投诉处理"),
    KNOWLEDGE("knowledge", "知识问答"),
    RISK_CONTROL("risk_control", "风控审核"),
    HUMAN_SERVICE("human_service", "人工转接"),
    CHITCHAT("chitchat", "通用闲聊");

    private final String code;
    private final String label;

    IntentType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static IntentType fromCode(String code) {
        for (IntentType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return CHITCHAT;
    }
}
