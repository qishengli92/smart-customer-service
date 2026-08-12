package com.cs.infra.agentscope;

import com.cs.infra.observability.LangFuseTracer;
import com.cs.infra.observability.TraceContext;
import com.cs.infra.observability.TraceSpan;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * AgentScope 2.0 Middleware：从 onModelCall / onAgent 提取模型名与 Token，写入 LangFuse Generation。
 * <p>
 * 替代已废弃的 {@code Hook} / {@code PostCallEvent} / {@code PostReasoningEvent}。
 * RuntimeContext 由框架注入，避免仅依赖 ThreadLocal 在 Reactor 线程丢 session。
 * <p>
 * 注册方式：{@code ReActAgent.builder().middleware(this)}（勿再用 {@code .hook(...)}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangFuseAgentMiddleware implements MiddlewareBase {

    private final LangFuseTracer langFuseTracer;

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        StringBuilder text = new StringBuilder();
        String modelName = resolveModelName(agent, input.model());
        Map<String, Object> params = toModelParams(input.options());
        if (params.isEmpty() && agent instanceof ReActAgent reAct) {
            params = toModelParams(reAct.getGenerateOptions());
        }
        Map<String, Object> modelParams = params;
        String agentName = agent != null ? agent.getName() : "react-agent";

        return next.apply(input)
                .doOnNext(event -> {
                    try {
                        if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                            text.append(delta.getDelta());
                        } else if (event instanceof ModelCallEndEvent end) {
                            record(ctx,
                                    agentName + "-reasoning",
                                    modelName,
                                    modelParams,
                                    text.toString(),
                                    end.getUsage(),
                                    "reasoning");
                        }
                    } catch (Exception e) {
                        log.warn("LangFuse generation middleware (model_call) failed: {}", e.getMessage());
                    }
                });
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String agentName = agent != null ? agent.getName() : "react-agent";
        return next.apply(input)
                .doOnNext(event -> {
                    try {
                        if (event instanceof AgentResultEvent result) {
                            Msg msg = result.getResult();
                            String modelName = null;
                            Map<String, Object> params = new LinkedHashMap<>();
                            if (agent instanceof ReActAgent reAct) {
                                if (reAct.getModel() != null) {
                                    modelName = reAct.getModel().getModelName();
                                }
                                params = toModelParams(reAct.getGenerateOptions());
                            }
                            ChatUsage usage = msg != null ? msg.getChatUsage() : null;
                            String content = msg != null ? msg.getTextContent() : null;
                            record(ctx, agentName, modelName, params, content, usage, "post_call");
                        }
                    } catch (Exception e) {
                        log.warn("LangFuse generation middleware (agent) failed: {}", e.getMessage());
                    }
                });
    }

    /**
     * Agent 在 call().block() 之后主动补记（防止中间件未触发时漏报）。
     */
    public void afterAgentCall(ReActAgent agent, Msg msg) {
        if (msg == null) {
            return;
        }
        String modelName = null;
        Map<String, Object> params = new LinkedHashMap<>();
        if (agent != null) {
            if (agent.getModel() != null) {
                modelName = agent.getModel().getModelName();
            }
            params = toModelParams(agent.getGenerateOptions());
        }
        record(null,
                agent != null ? agent.getName() : "react-agent",
                modelName,
                params,
                msg.getTextContent(),
                msg.getChatUsage(),
                "agent_call_result");
    }

    private void record(RuntimeContext ctx,
                        String name,
                        String modelName,
                        Map<String, Object> modelParams,
                        String content,
                        ChatUsage usage,
                        String source) {
        String sessionId = resolveSessionId(ctx);
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("LangFuse skip generation (no sessionId): source={}, model={}", source, modelName);
            return;
        }

        TraceSpan.TokenUsage tokenUsage = null;
        if (usage != null) {
            int total = usage.getTotalTokens() > 0
                    ? usage.getTotalTokens()
                    : usage.getInputTokens() + usage.getOutputTokens();
            tokenUsage = TraceSpan.TokenUsage.builder()
                    .promptTokens(usage.getInputTokens())
                    .completionTokens(usage.getOutputTokens())
                    .totalTokens(total)
                    .build();
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("source", source);
        input.put("agent", name);

        Map<String, Object> output = new LinkedHashMap<>();
        if (content != null && !content.isBlank()) {
            output.put("content", content.length() > 2000 ? content.substring(0, 2000) + "..." : content);
        }
        if (modelName != null) {
            output.put("model", modelName);
        }
        if (tokenUsage != null) {
            output.put("promptTokens", tokenUsage.getPromptTokens());
            output.put("completionTokens", tokenUsage.getCompletionTokens());
            output.put("totalTokens", tokenUsage.getTotalTokens());
        }

        Instant end = Instant.now();
        long durationMs = usage != null ? Math.max(1, (long) (usage.getTime() * 1000)) : 1;
        langFuseTracer.recordGeneration(
                sessionId,
                TraceContext.getParentSpanId(),
                name,
                modelName,
                modelParams == null || modelParams.isEmpty() ? null : modelParams,
                input,
                output,
                tokenUsage,
                end.minusMillis(durationMs),
                end);
    }

    private String resolveSessionId(RuntimeContext ctx) {
        if (ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            return ctx.getSessionId();
        }
        return TraceContext.getSessionId();
    }

    private String resolveModelName(Agent agent, Model model) {
        if (model != null && model.getModelName() != null && !model.getModelName().isBlank()) {
            return model.getModelName();
        }
        if (agent instanceof ReActAgent reAct && reAct.getModel() != null) {
            return reAct.getModel().getModelName();
        }
        return null;
    }

    private Map<String, Object> toModelParams(GenerateOptions options) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (options == null) {
            return params;
        }
        if (options.getTemperature() != null) {
            params.put("temperature", options.getTemperature());
        }
        if (options.getMaxTokens() != null) {
            params.put("max_tokens", options.getMaxTokens());
        }
        if (options.getTopP() != null) {
            params.put("top_p", options.getTopP());
        }
        return params;
    }

    @Override
    public int order() {
        return 100;
    }
}
