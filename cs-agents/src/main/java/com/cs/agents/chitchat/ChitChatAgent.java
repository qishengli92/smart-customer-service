package com.cs.agents.chitchat;

import com.cs.agents.support.AgentMemorySupport;
import com.cs.infra.agentscope.LangFuseAgentMiddleware;
import com.cs.infra.observability.TraceContext;
import com.cs.memory.agentscope.MilvusLongTermMemory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 闲聊 / 兜底 Agent：AgentScope {@link ReActAgent} + chitchat 档低成本模型。
 * <p>
 * 挂载 AgentStateStore（短期）与 LongTermMemory（长期）。
 */
@Slf4j
@Component
public class ChitChatAgent {

    private static final String AGENT_NAME = "chitchat";

    private static final String SYSTEM_PROMPT = """
            你是友好的智能客服助手。处理日常闲聊、打招呼、简单引导。
            规则：
            1. 保持友好和温暖，使用简洁中文回复
            2. 在闲聊中适当引导用户表达具体需求（订单、售后、保修、发票、产品等）
            3. 不要编造业务政策、价格、库存或订单状态
            """;

    private static final String FALLBACK = """
            您好！我是智能客服助手，很高兴为您服务。
            我可以帮您咨询产品、查询订单物流、办理退款退货、回答保修/发票等问题。
            请告诉我您需要什么帮助？
            """;

    private final ReActAgent agent;
    private final LangFuseAgentMiddleware langFuseAgentMiddleware;

    public ChitChatAgent(@Qualifier("chitchatChatModel") DashScopeChatModel chatModel,
                         LangFuseAgentMiddleware langFuseAgentMiddleware,
                         AgentStateStore agentStateStore,
                         MilvusLongTermMemory longTermMemory) {
        this.langFuseAgentMiddleware = langFuseAgentMiddleware;
        this.agent = AgentMemorySupport.applyMemory(
                        ReActAgent.builder()
                                .name(AGENT_NAME)
                                .sysPrompt(SYSTEM_PROMPT)
                                .model(chatModel)
                                .middleware(langFuseAgentMiddleware)
                                .maxIters(3),
                        agentStateStore,
                        longTermMemory)
                .build();
    }

    public String handle(String userMessage, String context) {
        log.info("ChitChatAgent handling: {}", userMessage.substring(0, Math.min(50, userMessage.length())));
        try {
            String prompt = (context != null && !context.isBlank())
                    ? "参考信息：\n" + context + "\n\n用户消息：\n" + userMessage
                    : userMessage;
            Msg reply = agent.call(prompt, AgentMemorySupport.runtimeContext(
                    TraceContext.getSessionId(), TraceContext.getUserId(), AGENT_NAME))
                    .block(Duration.ofSeconds(90));
            langFuseAgentMiddleware.afterAgentCall(agent, reply);
            String text = reply != null ? reply.getTextContent() : null;
            return (text != null && !text.isBlank()) ? text : FALLBACK;
        } catch (Exception e) {
            log.warn("ChitChatAgent ReActAgent failed: {}", e.getMessage());
            return FALLBACK;
        }
    }
}
