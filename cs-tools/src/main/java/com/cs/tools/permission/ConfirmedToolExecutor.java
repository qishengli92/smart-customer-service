package com.cs.tools.permission;

import com.cs.common.enums.PendingActionStatus;
import com.cs.common.model.AfterSalesTicket;
import com.cs.common.model.PendingAction;
import com.cs.infra.persistence.ConversationPersistenceService;
import com.cs.tools.refund.RefundTool;
import com.cs.tools.risk.RiskAssessTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 确认后幂等执行写工具，返回模板话术（不恢复 ReAct）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmedToolExecutor {

    private final PendingActionStore pendingActionStore;
    private final RefundTool refundTool;
    private final RiskAssessTool riskAssessTool;
    private final ConversationPersistenceService persistence;

    public String approve(PendingAction action) {
        if (action.getStatus() == PendingActionStatus.EXECUTED && action.getResult() != null) {
            return templateFromResult(action);
        }
        if (action.getStatus() == PendingActionStatus.EXPIRED) {
            return "该确认已超时，请重新发起操作。";
        }
        if (action.getStatus() != PendingActionStatus.PENDING
                && action.getStatus() != PendingActionStatus.APPROVED) {
            return "该确认单已处理，无需重复操作。";
        }

        action.setStatus(PendingActionStatus.APPROVED);
        Object result = executeTool(action);
        action.setResult(result);
        action.setStatus(PendingActionStatus.EXECUTED);
        pendingActionStore.save(action);
        persistence.saveToolCallLog(
                action.getSessionId(), action.getUserId(), action.getTenantId(),
                action.getToolName(), action.getConfirmationId(), action.getIdempotencyKey(),
                action.getArguments(), result, "EXECUTED");
        return templateFromResult(action);
    }

    public String reject(PendingAction action) {
        action.setStatus(PendingActionStatus.REJECTED);
        pendingActionStore.save(action);
        persistence.saveToolCallLog(
                action.getSessionId(), action.getUserId(), action.getTenantId(),
                action.getToolName(), action.getConfirmationId(), action.getIdempotencyKey(),
                action.getArguments(), Map.of("decision", "REJECTED"), "REJECTED");
        return "已取消操作，如需继续请重新说明您的需求。";
    }

    private Object executeTool(PendingAction action) {
        Map<String, Object> args = action.getArguments();
        String tool = action.getToolName();
        log.info("Executing confirmed tool: {} args={}", tool, args);

        if ("apply_refund".equals(tool)) {
            String orderId = String.valueOf(args.get("orderId"));
            String reason = args.getOrDefault("reason", "用户确认退款").toString();
            Double amount = args.get("amount") instanceof Number n ? n.doubleValue() : null;
            AfterSalesTicket ticket = refundTool.applyRefund(orderId, reason, amount);
            riskAssessTool.recordRefundExecuted(action.getUserId());
            return ticket;
        }
        if ("apply_return".equals(tool)) {
            String orderId = String.valueOf(args.get("orderId"));
            String reason = args.getOrDefault("reason", "用户确认退货").toString();
            return refundTool.applyReturn(orderId, reason);
        }
        throw new IllegalArgumentException("Unsupported tool: " + tool);
    }

    private String templateFromResult(PendingAction action) {
        Object result = action.getResult();
        if (result instanceof AfterSalesTicket ticket) {
            return String.format("""
                    操作已确认并提交成功！
                    
                    📋 售后单号：%s
                    📦 订单号：%s
                    📌 类型：%s
                    📌 状态：%s
                    %s
                    """,
                    ticket.getTicketId(),
                    ticket.getOrderId(),
                    ticket.getType(),
                    ticket.getStatus(),
                    ticket.getRefundAmount() != null
                            ? String.format("💰 退款金额：¥%.2f\n退款预计1-3个工作日到账。", ticket.getRefundAmount())
                            : "请按指引寄回商品。");
        }
        return "操作已完成：" + result;
    }
}
