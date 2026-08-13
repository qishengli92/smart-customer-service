package com.cs.infra.agentscope;

import com.cs.infra.config.LangFuseProperties;
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
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tracing.NoopTracer;
import io.agentscope.core.tracing.TracerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * AgentScope Middleware：Track A 兜底（ingestion Generation / Tool Span）。
 * <p>
 * 当 {@code cs.observability.langfuse.otel-enabled=true}（双轨 Track B）时，
 * LLM Prompt / Tool 由 {@code GenAiOtelTracer} 经 OTLP 上报，本中间件跳过以避免重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangFuseAgentMiddleware implements MiddlewareBase {

    private static final int MAX_FIELD_CHARS = 2000;
    private static final int MAX_MESSAGES = 40;

    private final LangFuseTracer langFuseTracer;
    private final LangFuseProperties langFuseProperties;

    @SuppressWarnings("deprecation")
    private boolean otelTrackActive() {
        if (!langFuseProperties.isEnabled() || !langFuseProperties.isOtelEnabled()) {
            return false;
        }
        // 仅当 GenAiOtelTracer 已成功注册时跳过，避免密钥未配置时两边都空
        return !(TracerRegistry.get() instanceof NoopTracer);
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (otelTrackActive()) {
            return next.apply(input);
        }
        StringBuilder text = new StringBuilder();
        String modelName = resolveModelName(agent, input.model());
        Map<String, Object> params = toModelParams(input.options());
        if (params.isEmpty() && agent instanceof ReActAgent reAct) {
            params = toModelParams(reAct.getGenerateOptions());
        }
        Map<String, Object> modelParams = params;
        String agentName = agent != null ? agent.getName() : "react-agent";
        Map<String, Object> promptInput = buildPromptInput(agentName, "reasoning", input);

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
                                    promptInput,
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
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (otelTrackActive()) {
            return next.apply(input);
        }
        String sessionId = resolveSessionId(ctx);
        String parentSpanId = TraceContext.getParentSpanId();
        Map<String, String> spanByCallId = new ConcurrentHashMap<>();
        Map<String, StringBuilder> resultByCallId = new ConcurrentHashMap<>();

        if (sessionId != null && !sessionId.isBlank() && input.toolCalls() != null) {
            for (ToolUseBlock toolCall : input.toolCalls()) {
                if (toolCall == null || toolCall.getName() == null) {
                    continue;
                }
                String callId = toolCall.getId() != null ? toolCall.getId() : toolCall.getName();
                Map<String, Object> toolInput = new LinkedHashMap<>();
                toolInput.put("toolCallId", callId);
                toolInput.put("name", toolCall.getName());
                toolInput.put("arguments", truncateMapValues(toolCall.getInput()));
                if (agent != null && agent.getName() != null) {
                    toolInput.put("agent", agent.getName());
                }
                try {
                    String spanId = langFuseTracer.startToolSpan(
                            sessionId, toolCall.getName(), toolInput, parentSpanId);
                    spanByCallId.put(callId, spanId);
                    resultByCallId.put(callId, new StringBuilder());
                } catch (Exception e) {
                    log.warn("LangFuse start tool span failed: tool={}, err={}",
                            toolCall.getName(), e.getMessage());
                }
            }
        } else if (sessionId == null || sessionId.isBlank()) {
            log.warn("LangFuse skip tool spans (no sessionId), tools={}",
                    input.toolCalls() != null ? input.toolCalls().size() : 0);
        }

        return next.apply(input)
                .doOnNext(event -> {
                    try {
                        if (event instanceof ToolResultTextDeltaEvent delta
                                && delta.getToolCallId() != null
                                && delta.getDelta() != null) {
                            resultByCallId
                                    .computeIfAbsent(delta.getToolCallId(), id -> new StringBuilder())
                                    .append(delta.getDelta());
                        } else if (event instanceof ToolResultEndEvent end
                                && end.getToolCallId() != null) {
                            finishToolSpan(spanByCallId, resultByCallId, end.getToolCallId(),
                                    end.getState() != null ? end.getState().name() : "ok", null);
                        }
                    } catch (Exception e) {
                        log.warn("LangFuse tool middleware failed: {}", e.getMessage());
                    }
                })
                .doOnError(err -> finishAllToolSpans(spanByCallId, resultByCallId, err.getMessage()))
                .doOnComplete(() -> finishAllToolSpans(spanByCallId, resultByCallId, null))
                .doOnCancel(() -> finishAllToolSpans(spanByCallId, resultByCallId, "cancelled"));
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (otelTrackActive()) {
            return next.apply(input);
        }
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
                            Map<String, Object> promptInput = new LinkedHashMap<>();
                            promptInput.put("source", "post_call");
                            promptInput.put("agent", agentName);
                            if (input != null && input.msgs() != null && !input.msgs().isEmpty()) {
                                promptInput.put("messages", serializeMessages(input.msgs()));
                            }
                            record(ctx, agentName, modelName, params, promptInput, content, usage, "post_call");
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
        if (msg == null || otelTrackActive()) {
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
        String agentName = agent != null ? agent.getName() : "react-agent";
        Map<String, Object> promptInput = new LinkedHashMap<>();
        promptInput.put("source", "agent_call_result");
        promptInput.put("agent", agentName);
        record(null,
                agentName,
                modelName,
                params,
                promptInput,
                msg.getTextContent(),
                msg.getChatUsage(),
                "agent_call_result");
    }

    private void finishToolSpan(Map<String, String> spanByCallId,
                                Map<String, StringBuilder> resultByCallId,
                                String callId,
                                String status,
                                String errorMessage) {
        String spanId = spanByCallId.remove(callId);
        if (spanId == null) {
            return;
        }
        StringBuilder result = resultByCallId.remove(callId);
        if (errorMessage != null) {
            langFuseTracer.endSpanWithError(spanId, errorMessage);
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("toolCallId", callId);
        output.put("status", status);
        if (result != null && !result.isEmpty()) {
            output.put("content", truncate(result.toString()));
        }
        langFuseTracer.endSpan(spanId, output, null);
    }

    private void finishAllToolSpans(Map<String, String> spanByCallId,
                                    Map<String, StringBuilder> resultByCallId,
                                    String errorMessage) {
        if (spanByCallId.isEmpty()) {
            return;
        }
        List<String> remaining = new ArrayList<>(spanByCallId.keySet());
        for (String callId : remaining) {
            finishToolSpan(spanByCallId, resultByCallId, callId,
                    errorMessage != null ? "error" : "ok", errorMessage);
        }
    }

    private Map<String, Object> buildPromptInput(String agentName, String source, ModelCallInput input) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("source", source);
        prompt.put("agent", agentName);
        if (input != null) {
            if (input.messages() != null && !input.messages().isEmpty()) {
                prompt.put("messages", serializeMessages(input.messages()));
            }
            if (input.tools() != null && !input.tools().isEmpty()) {
                prompt.put("tools", serializeToolSchemas(input.tools()));
            }
        }
        return prompt;
    }

    private List<Map<String, Object>> serializeMessages(List<Msg> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        int limit = Math.min(messages.size(), MAX_MESSAGES);
        int start = Math.max(0, messages.size() - limit);
        if (start > 0) {
            Map<String, Object> omitted = new LinkedHashMap<>();
            omitted.put("role", "system");
            omitted.put("content", "[omitted " + start + " earlier messages]");
            out.add(omitted);
        }
        for (int i = start; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            if (msg == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", msg.getRole() != null ? msg.getRole().name().toLowerCase() : "unknown");
            if (msg.getName() != null && !msg.getName().isBlank()) {
                item.put("name", msg.getName());
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                item.put("content", truncate(text));
            }
            List<Map<String, Object>> toolCalls = serializeToolUses(msg);
            if (!toolCalls.isEmpty()) {
                item.put("tool_calls", toolCalls);
            }
            List<Map<String, Object>> toolResults = serializeToolResults(msg);
            if (!toolResults.isEmpty()) {
                item.put("tool_results", toolResults);
            }
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> serializeToolUses(Msg msg) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolUseBlock block : msg.getContentBlocks(ToolUseBlock.class)) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", block.getId());
            call.put("name", block.getName());
            call.put("arguments", truncateMapValues(block.getInput()));
            list.add(call);
        }
        return list;
    }

    private List<Map<String, Object>> serializeToolResults(Msg msg) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", block.getId());
            result.put("name", block.getName());
            result.put("content", truncate(toolResultText(block)));
            if (block.getState() != null) {
                result.put("status", block.getState().name());
            }
            list.add(result);
        }
        return list;
    }

    private String toolResultText(ToolResultBlock block) {
        if (block.getOutput() == null || block.getOutput().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock contentBlock : block.getOutput()) {
            if (contentBlock instanceof TextBlock textBlock && textBlock.getText() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(textBlock.getText());
            } else if (contentBlock != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(contentBlock);
            }
        }
        return sb.toString();
    }

    private List<Map<String, Object>> serializeToolSchemas(List<ToolSchema> tools) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolSchema schema : tools) {
            if (schema == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", schema.getName());
            if (schema.getDescription() != null) {
                item.put("description", truncate(schema.getDescription()));
            }
            list.add(item);
        }
        return list;
    }

    private void record(RuntimeContext ctx,
                        String name,
                        String modelName,
                        Map<String, Object> modelParams,
                        Map<String, Object> promptInput,
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

        Map<String, Object> input = promptInput != null ? new LinkedHashMap<>(promptInput) : new LinkedHashMap<>();
        input.putIfAbsent("source", source);
        input.putIfAbsent("agent", name);

        Map<String, Object> output = new LinkedHashMap<>();
        if (content != null && !content.isBlank()) {
            output.put("content", truncate(content));
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

    private Map<String, Object> truncateMapValues(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                copy.put(entry.getKey(), truncate(s));
            } else if (value != null) {
                String asText = String.valueOf(value);
                copy.put(entry.getKey(), asText.length() > MAX_FIELD_CHARS
                        ? truncate(asText) : value);
            } else {
                copy.put(entry.getKey(), null);
            }
        }
        return copy;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_FIELD_CHARS ? value.substring(0, MAX_FIELD_CHARS) + "..." : value;
    }

    @Override
    public int order() {
        return 100;
    }
}
