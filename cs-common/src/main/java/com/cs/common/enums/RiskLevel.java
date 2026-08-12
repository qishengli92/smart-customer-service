package com.cs.common.enums;

/**
 * 风险等级枚举
 */
public enum RiskLevel {

    LOW("low", "低风险"),
    MEDIUM("medium", "中风险"),
    HIGH("high", "高风险"),
    CRITICAL("critical", "极高风险");

    private final String code;
    private final String label;

    RiskLevel(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
}
