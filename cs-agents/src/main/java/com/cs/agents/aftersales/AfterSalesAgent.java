package com.cs.agents.aftersales;

import com.cs.common.enums.PermissionMode;
import com.cs.common.model.AgentHandleResult;
import com.cs.common.model.OrderInfo;
import com.cs.common.model.PendingAction;
import com.cs.common.model.PermissionDecision;
import com.cs.common.model.RiskAssessResult;
import com.cs.infra.agentscope.LangFuseAgentMiddleware;
import com.cs.infra.observability.TraceContext;
import com.cs.tools.order.OrderQueryTool;
import com.cs.tools.permission.PendingActionStore;
import com.cs.tools.permission.PermissionGate;
import com.cs.tools.risk.RiskAssessTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 售后 Agent —— PermissionGate 控写操作；话术由 AgentScope {@link ReActAgent} 生成
 */
@Slf4j
@Component
public class AfterSalesAgent {

    private static final String SYSTEM_PROMPT = """
            你是售后支持专员。根据系统事实向用户说明退款/退货与确认事项。
            规则：
            1. 不得编造订单、金额、confirmationId
            2. 若参考信息要求用户确认，必须在回复中保留 confirmationId，并提示回复「确认」或「取消」
            3. 语气专业、共情，简洁中文
            """;

    private final OrderQueryTool orderQueryTool;
    private final RiskAssessTool riskAssessTool;
    private final PermissionGate permissionGate;
    private final PendingActionStore pendingActionStore;
    private final ReActAgent agent;
    private final LangFuseAgentMiddleware langFuseAgentMiddleware;

    public AfterSalesAgent(OrderQueryTool orderQueryTool,
                           RiskAssessTool riskAssessTool,
                           PermissionGate permissionGate,
                           PendingActionStore pendingActionStore,
                           @Qualifier("expertChatModel") DashScopeChatModel chatModel,
                           LangFuseAgentMiddleware langFuseAgentMiddleware) {
        this.orderQueryTool = orderQueryTool;
        this.riskAssessTool = riskAssessTool;
        this.permissionGate = permissionGate;
        this.pendingActionStore = pendingActionStore;
        this.langFuseAgentMiddleware = langFuseAgentMiddleware;
        this.agent = ReActAgent.builder()
                .name("after_sales")
                .sysPrompt(SYSTEM_PROMPT)
                .model(chatModel)
                .middleware(langFuseAgentMiddleware)
                .maxIters(3)
                .build();
    }

    public AgentHandleResult handle(String userMessage, String context,
                                    String sessionId, String userId, String tenantId) {
        log.info("AfterSalesAgent handling: {}", userMessage.substring(0, Math.min(50, userMessage.length())));

        String orderId = extractOrderId(userMessage);
        boolean isRefund = userMessage.contains("退款") || userMessage.contains("退钱");

        if (orderId != null) {
            OrderInfo order = orderQueryTool.queryOrder(orderId);
            if (order != null) {
                if (isRefund) {
                    return handleRefund(order, userMessage, sessionId, userId, tenantId);
                }
                return handleReturn(order, userMessage, sessionId, userId, tenantId);
            }
            return AgentHandleResult.text(llm(
                    userMessage,
                    "系统查询结果：未找到订单号 " + orderId,
                    "抱歉，未找到订单号 " + orderId + " 的相关信息。请确认订单号是否正确。"));
        }

        return AgentHandleResult.text(llm(
                userMessage,
                "用户尚未提供订单号。请引导其提供 ORD 开头订单号，以便办理退款/退货。",
                "我是您的售后支持专员，可以帮您处理退款、退货、换货等售后问题。\n请提供您的订单号，我来为您处理。"));
    }

    private AgentHandleResult handleRefund(OrderInfo order, String userMessage,
                                           String sessionId, String userId, String tenantId) {
        Double refundAmount = order.getAmount();
        RiskAssessResult risk = riskAssessTool.assess("REFUND", refundAmount, order.getUserId(), userMessage);
        PermissionDecision decision = permissionGate.evaluate("apply_refund", refundAmount, risk, true);

        if (decision.getMode() == PermissionMode.DENY) {
            String fallback = String.format(
                    "您的退款申请（订单 %s，金额 ¥%.2f）无法自动处理：%s\n建议转接人工客服协助。",
                    order.getOrderId(), refundAmount, decision.getReason());
            String reply = llm(userMessage,
                    "权限结论：拒绝自动退款。原因：" + decision.getReason()
                            + "\n订单：" + order.getOrderId() + " 金额：" + refundAmount,
                    fallback);
            return AgentHandleResult.handoff(reply);
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderId", order.getOrderId());
        args.put("reason", "用户申请退款");
        args.put("amount", refundAmount);

        if (decision.getMode() == PermissionMode.CONFIRM) {
            try {
                PendingAction pending = pendingActionStore.create(
                        sessionId, userId, tenantId,
                        "apply_refund", args, decision.getPermission(),
                        String.format("退款 订单%s ¥%.2f", order.getOrderId(), refundAmount));
                String fallback = String.format("""
                        请确认以下退款操作：
                        
                        📦 订单号：%s
                        💰 退款金额：¥%.2f
                        ⚠️ %s
                        
                        回复「确认」或调用确认接口后将提交退款；回复「取消」则放弃。
                        confirmationId=%s
                        """,
                        order.getOrderId(), refundAmount, decision.getReason(), pending.getConfirmationId());
                String facts = """
                        待确认退款：
                        订单号=%s
                        退款金额=%.2f
                        原因=%s
                        confirmationId=%s
                        必须在回复中原样保留 confirmationId=%s
                        """.formatted(order.getOrderId(), refundAmount, decision.getReason(),
                        pending.getConfirmationId(), pending.getConfirmationId());
                String prompt = llm(userMessage, facts, fallback);
                if (!prompt.contains(pending.getConfirmationId())) {
                    prompt = prompt + "\nconfirmationId=" + pending.getConfirmationId();
                }
                return AgentHandleResult.pending(pending, prompt);
            } catch (IllegalStateException e) {
                return AgentHandleResult.text(e.getMessage());
            }
        }

        return AgentHandleResult.text("系统配置异常：退款未进入确认流，请联系管理员。");
    }

    private AgentHandleResult handleReturn(OrderInfo order, String userMessage,
                                           String sessionId, String userId, String tenantId) {
        RiskAssessResult risk = riskAssessTool.assess("RETURN", null, order.getUserId(), userMessage);
        PermissionDecision decision = permissionGate.evaluate("apply_return", null, risk, true);

        if (decision.getMode() == PermissionMode.DENY) {
            String fallback = "退货申请无法自动处理：" + decision.getReason();
            return AgentHandleResult.handoff(llm(userMessage,
                    "权限结论：拒绝。原因：" + decision.getReason(), fallback));
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderId", order.getOrderId());
        args.put("reason", "用户申请退货");

        try {
            PendingAction pending = pendingActionStore.create(
                    sessionId, userId, tenantId,
                    "apply_return", args, decision.getPermission(),
                    "退货 订单" + order.getOrderId());
            String fallback = String.format("""
                    请确认以下退货操作：
                    
                    📦 订单号：%s
                    
                    回复「确认」后将创建退货单；回复「取消」则放弃。
                    confirmationId=%s
                    """,
                    order.getOrderId(), pending.getConfirmationId());
            String facts = """
                    待确认退货：
                    订单号=%s
                    confirmationId=%s
                    必须在回复中原样保留 confirmationId=%s
                    """.formatted(order.getOrderId(), pending.getConfirmationId(), pending.getConfirmationId());
            String prompt = llm(userMessage, facts, fallback);
            if (!prompt.contains(pending.getConfirmationId())) {
                prompt = prompt + "\nconfirmationId=" + pending.getConfirmationId();
            }
            return AgentHandleResult.pending(pending, prompt);
        } catch (IllegalStateException e) {
            return AgentHandleResult.text(e.getMessage());
        }
    }

    private String llm(String userMessage, String facts, String fallback) {
        try {
            String prompt = "参考信息：\n" + facts + "\n\n用户消息：\n" + userMessage;
            Msg reply = agent.call(prompt, RuntimeContext.builder()
                    .sessionId(TraceContext.getSessionId())
                    .userId(TraceContext.getUserId())
                    .build()).block(Duration.ofSeconds(90));
            langFuseAgentMiddleware.afterAgentCall(agent, reply);
            String text = reply != null ? reply.getTextContent() : null;
            return (text != null && !text.isBlank()) ? text : fallback;
        } catch (Exception e) {
            log.warn("AfterSalesAgent ReActAgent failed: {}", e.getMessage());
            return fallback;
        }
    }

    private String extractOrderId(String message) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("ORD\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group() : null;
    }
}
