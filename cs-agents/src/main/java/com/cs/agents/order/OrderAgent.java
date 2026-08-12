package com.cs.agents.order;

import com.cs.infra.agentscope.LangFuseAgentMiddleware;
import com.cs.infra.observability.TraceContext;
import com.cs.tools.order.OrderQueryTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 订单领域 Agent：AgentScope {@link ReActAgent} + {@link Toolkit}({@link OrderQueryTool})。
 * <p>
 * 使用 expert 档 {@link DashScopeChatModel}，挂载 {@link LangFuseAgentMiddleware}；
 * 工具经 {@code @Tool} 注册，由 ReAct 循环自行调用（查单/改址）。
 */
@Slf4j
@Component
public class OrderAgent {

    private static final String SYSTEM_PROMPT = """
            你是订单服务专员。可调用工具查询订单、修改未发货订单地址。
            规则：
            1. 需要订单号时先向用户索取（ORD 开头）
            2. 只能依据工具返回的事实回答，禁止编造物流或状态
            3. 已发货订单不能改地址，应引导售后
            4. 退款/退货请引导用户说明需求并提供订单号
            5. 用简洁中文回复
            """;

    private static final String FALLBACK =
            "我是您的订单服务专员，可以帮您查询订单、追踪物流或修改地址。\n请提供您的订单号（以ORD开头）。";

    private final ReActAgent agent;
    private final LangFuseAgentMiddleware langFuseAgentMiddleware;

    public OrderAgent(OrderQueryTool orderQueryTool,
                      @Qualifier("expertChatModel") DashScopeChatModel chatModel,
                      LangFuseAgentMiddleware langFuseAgentMiddleware) {
        this.langFuseAgentMiddleware = langFuseAgentMiddleware;
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(orderQueryTool);

        this.agent = ReActAgent.builder()
                .name("order")
                .sysPrompt(SYSTEM_PROMPT)
                .model(chatModel)
                .toolkit(toolkit)
                .middleware(langFuseAgentMiddleware)
                .maxIters(6)
                .build();
    }

    public String handle(String userMessage, String context) {
        log.info("OrderAgent handling: {}", userMessage.substring(0, Math.min(50, userMessage.length())));
        try {
            String prompt = (context != null && !context.isBlank())
                    ? "补充上下文：\n" + context + "\n\n用户消息：\n" + userMessage
                    : userMessage;
            Msg reply = agent.call(prompt, RuntimeContext.builder()
                    .sessionId(TraceContext.getSessionId())
                    .userId(TraceContext.getUserId())
                    .build()).block(Duration.ofSeconds(120));
            langFuseAgentMiddleware.afterAgentCall(agent, reply);
            String text = reply != null ? reply.getTextContent() : null;
            return (text != null && !text.isBlank()) ? text : FALLBACK;
        } catch (Exception e) {
            log.warn("OrderAgent ReActAgent failed: {}", e.getMessage());
            return FALLBACK;
        }
    }
}
