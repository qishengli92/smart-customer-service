package com.cs.agents.knowledge;

import com.cs.agents.support.AgentMemorySupport;
import com.cs.infra.agentscope.LangFuseAgentMiddleware;
import com.cs.infra.observability.TraceContext;
import com.cs.knowledge.hook.KnowledgeRAGHook;
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
 * 知识问答 Agent —— 应用层 RAG + AgentScope {@link ReActAgent}。
 * <p>
 * RAG 使用自研 {@link KnowledgeRAGHook}；会话短期 / 跨会话长期走 AgentScope 原生记忆组件。
 */
@Slf4j
@Component
public class KnowledgeAgent {

    private static final String AGENT_NAME = "knowledge";

    private static final String SYSTEM_PROMPT = """
            你是智能客服知识问答专员。只能依据提供的「参考信息」回答用户问题。
            规则：
            1. 用简洁、友好的中文回答
            2. 不得编造参考信息中没有的政策、价格、时效或产品参数
            3. 若参考信息不足，明确说明「知识库暂未覆盖」，并建议换个问法或转人工
            4. 回答末尾可提示：如与订单实际情况不符，请提供订单号或转人工核实
            """;

    private final KnowledgeRAGHook ragHook;
    private final ReActAgent agent;
    private final LangFuseAgentMiddleware langFuseAgentMiddleware;

    public KnowledgeAgent(KnowledgeRAGHook ragHook,
                          @Qualifier("expertChatModel") DashScopeChatModel chatModel,
                          LangFuseAgentMiddleware langFuseAgentMiddleware,
                          AgentStateStore agentStateStore,
                          MilvusLongTermMemory longTermMemory) {
        this.ragHook = ragHook;
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
        log.info("KnowledgeAgent handling: {}",
                userMessage.substring(0, Math.min(50, userMessage.length())));

        String knowledgeContext = (context != null && !context.isBlank()
                && !context.contains("未检索到"))
                ? context
                : ragHook.beforeReasoning(userMessage, 5);

        if (knowledgeContext == null || knowledgeContext.isBlank()
                || knowledgeContext.contains("未检索到")) {
            return "抱歉，我暂时没有找到与您问题相关的信息。\n"
                    + "您可以尝试换个关键词（例如「发票」「保修」「退货政策」），"
                    + "或回复「转人工」由人工客服协助。";
        }

        String rag = stripInstructionTail(knowledgeContext);
        String fallback = """
                根据知识库，为您整理如下说明：

                %s

                以上信息仅供参考。若与您订单实际情况不一致，请提供订单号或转人工核实。
                """.formatted(rag);

        try {
            String prompt = "参考信息：\n" + rag + "\n\n用户问题：\n" + userMessage;
            Msg reply = agent.call(prompt, AgentMemorySupport.runtimeContext(
                    TraceContext.getSessionId(), TraceContext.getUserId(), AGENT_NAME))
                    .block(Duration.ofSeconds(90));
            langFuseAgentMiddleware.afterAgentCall(agent, reply);
            String text = reply != null ? reply.getTextContent() : null;
            return (text != null && !text.isBlank()) ? text : fallback;
        } catch (Exception e) {
            log.warn("KnowledgeAgent ReActAgent failed: {}", e.getMessage());
            return fallback;
        }
    }

    private String stripInstructionTail(String context) {
        int idx = context.indexOf("请基于以上信息回答");
        if (idx > 0) {
            return context.substring(0, idx).trim();
        }
        return context.trim();
    }
}
