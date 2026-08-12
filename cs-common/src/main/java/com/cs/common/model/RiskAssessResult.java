package com.cs.common.model;

import com.cs.common.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控评估结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessResult {

    /**
     * 风险等级
     */
    private RiskLevel riskLevel;

    /**
     * 是否需要人工审批
     */
    private boolean needApproval;

    /**
     * 评估理由
     */
    private String reason;

    /**
     * 风险评分 [0, 100]
     */
    private Integer riskScore;

    /**
     * 建议操作
     */
    private String suggestion;

    /**
     * 创建低风险结果（自动通过）
     */
    public static RiskAssessResult lowRisk(String reason) {
        return RiskAssessResult.builder()
                .riskLevel(RiskLevel.LOW)
                .needApproval(false)
                .reason(reason)
                .riskScore(10)
                .suggestion("自动通过")
                .build();
    }

    /**
     * 创建中风险结果（需审核）
     */
    public static RiskAssessResult mediumRisk(String reason, String suggestion) {
        return RiskAssessResult.builder()
                .riskLevel(RiskLevel.MEDIUM)
                .needApproval(true)
                .reason(reason)
                .riskScore(50)
                .suggestion(suggestion)
                .build();
    }

    /**
     * 创建高风险结果（必须人工审批）
     */
    public static RiskAssessResult highRisk(String reason) {
        return RiskAssessResult.builder()
                .riskLevel(RiskLevel.HIGH)
                .needApproval(true)
                .reason(reason)
                .riskScore(80)
                .suggestion("必须人工审批")
                .build();
    }
}
