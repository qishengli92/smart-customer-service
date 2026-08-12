package com.cs.tools.permission;

import com.cs.common.enums.PermissionMode;
import com.cs.common.enums.RiskLevel;
import com.cs.common.model.PermissionDecision;
import com.cs.common.model.RiskAssessResult;
import com.cs.infra.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 写操作唯一权限裁决点：合并工具敏感度 + {@link RiskAssessResult} → AUTO / CONFIRM / DENY。
 * <p>
 * 领域 Agent（如售后）必须经此 Gate，禁止旁路直接调退款等写工具；
 * 阈值参考 {@link AgentProperties#getRefundThreshold()}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionGate {

    private final AgentProperties agentProperties;

    /**
     * @param toolName   工具名，如 apply_refund / apply_return
     * @param amount     金额（可空）
     * @param risk       风控结果（可空，只读可省略）
     * @param writeTool  是否写操作
     */
    public PermissionDecision evaluate(String toolName, Double amount,
                                       RiskAssessResult risk, boolean writeTool) {
        String permission = toolName != null ? toolName.replace('_', ':') : "unknown";

        if (!writeTool) {
            return PermissionDecision.auto(permission, "只读操作");
        }

        // HIGH + 禁止自助（多次退款等）→ DENY，引导人工
        if (risk != null && risk.getRiskLevel() == RiskLevel.HIGH
                && risk.getReason() != null && risk.getReason().contains("24小时内")) {
            return PermissionDecision.deny(permission, risk.getReason());
        }

        double confirmAmount = agentProperties.getRefundThreshold();
        boolean overAmount = amount != null && amount >= confirmAmount;
        boolean riskNeedsConfirm = risk != null && (
                risk.getRiskLevel() == RiskLevel.MEDIUM
                        || risk.getRiskLevel() == RiskLevel.HIGH
                        || risk.isNeedApproval());

        // 写工具默认需确认；金额/风控进一步强制 CONFIRM
        if (overAmount || riskNeedsConfirm || isSensitiveWrite(toolName)) {
            String reason = risk != null && risk.getReason() != null
                    ? risk.getReason()
                    : String.format("写操作需用户确认（金额阈值 ¥%.0f）", confirmAmount);
            log.info("PermissionGate CONFIRM: tool={}, amount={}, reason={}", toolName, amount, reason);
            return PermissionDecision.confirm(permission, reason);
        }

        // 极低风险小额写：仍 CONFIRM（产品默认：所有退款/退货都确认，堵住资金风险）
        if (isSensitiveWrite(toolName)) {
            return PermissionDecision.confirm(permission, "敏感写操作需用户确认");
        }

        return PermissionDecision.auto(permission, "低风险自动执行");
    }

    private boolean isSensitiveWrite(String toolName) {
        if (toolName == null) {
            return false;
        }
        return toolName.contains("refund")
                || toolName.contains("return")
                || toolName.contains("cancel")
                || toolName.contains("compensate");
    }

    public PermissionMode modeOf(PermissionDecision decision) {
        return decision.getMode();
    }
}
