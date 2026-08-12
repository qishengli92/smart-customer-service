package com.cs.tools.risk;

import com.cs.common.enums.RiskLevel;
import com.cs.common.model.RiskAssessResult;
import com.cs.infra.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 退款等写操作前的风险评估，结果输入 {@link PermissionGate}。
 * <p>
 * MVP：金额阈值 + 同用户短时多次退款计数；生产可换外部风控服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskAssessTool {

    private final AgentProperties agentProperties;

    /**
     * 用户退款记录（简单风控：同一用户24小时内多次退款需标记）
     */
    private final Map<String, Integer> userRefundCount = new ConcurrentHashMap<>();

    /**
     * 风险评估
     */
    public RiskAssessResult assess(String operationType, Double amount,
                                    String userId, String context) {
        // 规则1：退款金额超过阈值
        if ("REFUND".equals(operationType) && amount != null) {
            if (amount > agentProperties.getCompensationThreshold()) {
                log.warn("Risk assess: HIGH risk, amount={} exceeds compensation threshold",
                        amount);
                return RiskAssessResult.highRisk(
                        String.format("退款金额 ¥%.2f 超过赔偿阈值 ¥%.2f",
                                amount, agentProperties.getCompensationThreshold()));
            }
            if (amount > agentProperties.getRefundThreshold()) {
                log.warn("Risk assess: MEDIUM risk, amount={} exceeds refund threshold",
                        amount);
                return RiskAssessResult.mediumRisk(
                        String.format("退款金额 ¥%.2f 超过退款阈值 ¥%.2f，需人工审核",
                                amount, agentProperties.getRefundThreshold()),
                        "建议由值班主管审核后执行");
            }
        }

        // 规则2：同一用户多次退款
        int count = userRefundCount.getOrDefault(userId, 0);
        if (count >= 3) {
            log.warn("Risk assess: HIGH risk, user {} has {} refunds in 24h", userId, count);
            return RiskAssessResult.highRisk(
                    String.format("用户 %s 24小时内已有%d次退款记录，需人工审核", userId, count));
        }

        // 规则3：赔偿操作必须人工确认
        if ("COMPENSATION".equals(operationType)) {
            return RiskAssessResult.mediumRisk("赔偿操作需人工确认", "由客服主管审批");
        }

        // 注意：退款次数在真正执行写操作后由执行器累加，评估阶段不计数

        log.info("Risk assess: LOW risk, operation={}, amount={}, user={}",
                operationType, amount, userId);
        return RiskAssessResult.lowRisk("操作风险低，自动通过");
    }

    /**
     * 写操作成功后记录退款次数
     */
    public void recordRefundExecuted(String userId) {
        userRefundCount.merge(userId, 1, Integer::sum);
    }
}
