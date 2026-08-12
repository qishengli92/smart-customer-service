package com.cs.agents.human;

import com.cs.common.model.AgentHandleResult;
import com.cs.common.model.HandoffRecord;
import com.cs.infra.agentscope.LangFuseAgentMiddleware;
import com.cs.infra.observability.TraceContext;
import com.cs.tools.handoff.HumanHandoffTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 人工协作 Agent —— 排队事实固定；话术由 AgentScope {@link ReActAgent} 生成
 */
@Slf4j
@Component
public class HumanCollabAgent {

    private static final String SYSTEM_PROMPT = """
            你是智能客服。用户要求转人工，请安抚并说明已进入排队。
            规则：必须在回复中保留交接单号与技能组，不得编造排队人数或等待时间。
            """;

    private final HumanHandoffTool humanHandoffTool;
    private final ReActAgent agent;
    private final LangFuseAgentMiddleware langFuseAgentMiddleware;

    public HumanCollabAgent(HumanHandoffTool humanHandoffTool,
                            @Qualifier("expertChatModel") DashScopeChatModel chatModel,
                            LangFuseAgentMiddleware langFuseAgentMiddleware) {
        this.humanHandoffTool = humanHandoffTool;
        this.langFuseAgentMiddleware = langFuseAgentMiddleware;
        this.agent = ReActAgent.builder()
                .name("human_service")
                .sysPrompt(SYSTEM_PROMPT)
                .model(chatModel)
                .middleware(langFuseAgentMiddleware)
                .maxIters(3)
                .build();
    }

    public AgentHandleResult handle(String userMessage, String context,
                                    String sessionId, String userId, String tenantId) {
        log.info("HumanCollabAgent handling: {}", userMessage.substring(0, Math.min(50, userMessage.length())));

        String summary = "用户请求人工服务";
        if (context != null && !context.isBlank()) {
            summary = context.length() > 200 ? context.substring(0, 200) + "..." : context;
        }

        HandoffRecord record = humanHandoffTool.enqueue(
                sessionId, tenantId, userId,
                "用户主动要求", "NORMAL", summary, Map.of());

        String fallback = String.format("""
                好的，我这就为您转接人工客服。
                
                %s
                
                交接单号：%s
                技能组：%s
                当前状态：排队中
                
                请稍候，坐席接入后将继续为您服务。
                """,
                humanHandoffTool.transferToHuman("用户主动要求", "NORMAL", summary),
                record.getId(),
                record.getSkillGroup());

        String facts = """
                已创建人工交接单。
                交接单号=%s
                技能组=%s
                状态=排队中
                摘要=%s
                请在回复中保留交接单号与技能组。
                """.formatted(record.getId(), record.getSkillGroup(), summary);

        String reply = fallback;
        try {
            Msg msg = agent.call("参考信息：\n" + facts + "\n\n用户消息：\n" + userMessage,
                    RuntimeContext.builder()
                            .sessionId(TraceContext.getSessionId())
                            .userId(TraceContext.getUserId())
                            .build()).block(Duration.ofSeconds(90));
            langFuseAgentMiddleware.afterAgentCall(agent, msg);
            if (msg != null && msg.getTextContent() != null && !msg.getTextContent().isBlank()) {
                reply = msg.getTextContent();
            }
        } catch (Exception e) {
            log.warn("HumanCollabAgent ReActAgent failed: {}", e.getMessage());
        }
        if (!reply.contains(record.getId())) {
            reply = reply + "\n交接单号：" + record.getId() + "\n技能组：" + record.getSkillGroup();
        }
        return AgentHandleResult.handoff(reply, record);
    }
}
