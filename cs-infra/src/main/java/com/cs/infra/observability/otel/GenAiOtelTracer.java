package com.cs.infra.observability.otel;

import com.cs.infra.observability.TraceContext;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * AgentScope {@link Tracer} 实现：按 GenAI 语义约定打点，经 OTLP 被 LangFuse 解析为完整 Generation/Tool。
 * <p>
 * 对齐 AgentScope-demo 的 {@code TelemetryTracer} 能力；2.0.2 核心包已移除该类，故在本工程内自研接入。
 */
@Slf4j
@SuppressWarnings("deprecation")
public class GenAiOtelTracer implements Tracer {

    private static final AttributeKey<String> GEN_AI_OPERATION_NAME =
            AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_PROVIDER_NAME =
            AttributeKey.stringKey("gen_ai.provider.name");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL =
            AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Double> GEN_AI_REQUEST_TEMPERATURE =
            AttributeKey.doubleKey("gen_ai.request.temperature");
    private static final AttributeKey<Double> GEN_AI_REQUEST_TOP_P =
            AttributeKey.doubleKey("gen_ai.request.top_p");
    private static final AttributeKey<Long> GEN_AI_REQUEST_MAX_TOKENS =
            AttributeKey.longKey("gen_ai.request.max_tokens");
    private static final AttributeKey<String> GEN_AI_INPUT_MESSAGES =
            AttributeKey.stringKey("gen_ai.input.messages");
    private static final AttributeKey<String> GEN_AI_OUTPUT_MESSAGES =
            AttributeKey.stringKey("gen_ai.output.messages");
    private static final AttributeKey<String> GEN_AI_TOOL_DEFINITIONS =
            AttributeKey.stringKey("gen_ai.tool.definitions");
    private static final AttributeKey<String> GEN_AI_TOOL_NAME =
            AttributeKey.stringKey("gen_ai.tool.name");
    private static final AttributeKey<String> GEN_AI_TOOL_CALL_ID =
            AttributeKey.stringKey("gen_ai.tool.call.id");
    private static final AttributeKey<String> GEN_AI_TOOL_CALL_ARGUMENTS =
            AttributeKey.stringKey("gen_ai.tool.call.arguments");
    private static final AttributeKey<String> GEN_AI_TOOL_CALL_RESULT =
            AttributeKey.stringKey("gen_ai.tool.call.result");
    private static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<String> GEN_AI_CONVERSATION_ID =
            AttributeKey.stringKey("gen_ai.conversation.id");
    private static final AttributeKey<String> GEN_AI_AGENT_NAME =
            AttributeKey.stringKey("gen_ai.agent.name");
    private static final AttributeKey<String> GEN_AI_AGENT_ID =
            AttributeKey.stringKey("gen_ai.agent.id");
    private static final AttributeKey<String> GEN_AI_RESPONSE_ID =
            AttributeKey.stringKey("gen_ai.response.id");
    private static final AttributeKey<String> GEN_AI_RESPONSE_FINISH_REASONS =
            AttributeKey.stringKey("gen_ai.response.finish_reasons");

    private final io.opentelemetry.api.trace.Tracer tracer;
    private final AutoCloseable shutdownHook;

    public GenAiOtelTracer(io.opentelemetry.api.trace.Tracer tracer, AutoCloseable shutdownHook) {
        this.tracer = tracer;
        this.shutdownHook = shutdownHook;
    }

    @Override
    public Mono<Msg> callAgent(AgentBase instance, List<Msg> inputMessages, Supplier<Mono<Msg>> agentCall) {
        return Mono.deferContextual(ctxView -> {
            Context parent = resolveParent(ctxView);
            Span span = tracer.spanBuilder("invoke_agent " + safeName(instance.getName()))
                    .setParent(parent)
                    .setAttribute(GEN_AI_OPERATION_NAME, "invoke_agent")
                    .setAttribute(GEN_AI_AGENT_NAME, nullToEmpty(instance.getName()))
                    .setAttribute(GEN_AI_AGENT_ID, nullToEmpty(instance.getAgentId()))
                    .startSpan();
            putConversationId(span);
            putIfPresent(span, GEN_AI_INPUT_MESSAGES, GenAiAttributeHelper.inputMessagesJson(inputMessages));

            Context otelCtx = span.storeInContext(parent);
            return ContextPropagationOperator.runWithContext(
                    agentCall.get()
                            .doOnSuccess(msg -> {
                                putIfPresent(span, GEN_AI_OUTPUT_MESSAGES,
                                        GenAiAttributeHelper.outputMessagesJson(msg));
                                span.setStatus(StatusCode.OK);
                            })
                            .doOnError(err -> {
                                span.setStatus(StatusCode.ERROR, err.getMessage());
                                span.recordException(err);
                            })
                            .doFinally(sig -> span.end()),
                    otelCtx);
        });
    }

    @Override
    public Flux<ChatResponse> callModel(
            ChatModelBase instance,
            List<Msg> inputMessages,
            List<ToolSchema> toolSchemas,
            GenerateOptions options,
            Supplier<Flux<ChatResponse>> modelCall) {
        return Flux.deferContextual(ctxView -> {
            Context parent = resolveParent(ctxView);
            String modelName = instance != null ? instance.getModelName() : "unknown";
            SpanBuilder builder = tracer.spanBuilder("chat " + modelName)
                    .setParent(parent)
                    .setAttribute(GEN_AI_OPERATION_NAME, "chat")
                    .setAttribute(GEN_AI_PROVIDER_NAME, "dashscope")
                    .setAttribute(GEN_AI_REQUEST_MODEL, nullToEmpty(modelName));
            applyGenerateOptions(builder, options);
            putConversationId(builder);
            putIfPresent(builder, GEN_AI_INPUT_MESSAGES, GenAiAttributeHelper.inputMessagesJson(inputMessages));
            putIfPresent(builder, GEN_AI_TOOL_DEFINITIONS, GenAiAttributeHelper.toolDefinitionsJson(toolSchemas));

            Span span = builder.startSpan();
            Context otelCtx = span.storeInContext(parent);
            ChatResponseAggregator aggregator = new ChatResponseAggregator();
            AtomicBoolean failed = new AtomicBoolean(false);

            return ContextPropagationOperator.runWithContext(
                    modelCall.get()
                            .doOnNext(aggregator::append)
                            .doOnError(err -> {
                                failed.set(true);
                                span.setStatus(StatusCode.ERROR, err.getMessage());
                                span.recordException(err);
                            })
                            .doFinally(sig -> {
                                ChatResponse response = aggregator.getResponse();
                                applyModelResponse(span, response);
                                if (!failed.get()) {
                                    span.setStatus(StatusCode.OK);
                                }
                                span.end();
                            }),
                    otelCtx);
        });
    }

    @Override
    public Mono<ToolResultBlock> callTool(
            Toolkit toolkit,
            ToolCallParam toolCallParam,
            Supplier<Mono<ToolResultBlock>> toolKitCall) {
        ToolUseBlock toolUse = toolCallParam != null ? toolCallParam.getToolUseBlock() : null;
        String toolName = toolUse != null ? toolUse.getName() : "unknown";
        return Mono.deferContextual(ctxView -> {
            Context parent = resolveParent(ctxView);
            SpanBuilder builder = tracer.spanBuilder("execute_tool " + toolName)
                    .setParent(parent)
                    .setAttribute(GEN_AI_OPERATION_NAME, "execute_tool")
                    .setAttribute(GEN_AI_TOOL_NAME, nullToEmpty(toolName));
            putConversationId(builder);
            if (toolUse != null) {
                putIfPresent(builder, GEN_AI_TOOL_CALL_ID, toolUse.getId());
                putIfPresent(builder, GEN_AI_TOOL_CALL_ARGUMENTS,
                        GenAiAttributeHelper.toolArgumentsJson(toolUse.getInput()));
            }
            Span span = builder.startSpan();
            Context otelCtx = span.storeInContext(parent);

            return ContextPropagationOperator.runWithContext(
                    toolKitCall.get()
                            .doOnSuccess(result -> {
                                if (result != null) {
                                    putIfPresent(span, GEN_AI_TOOL_CALL_RESULT,
                                            GenAiAttributeHelper.toolResultJson(result.getOutput()));
                                }
                                span.setStatus(StatusCode.OK);
                            })
                            .doOnError(err -> {
                                span.setStatus(StatusCode.ERROR, err.getMessage());
                                span.recordException(err);
                            })
                            .doFinally(sig -> span.end()),
                    otelCtx);
        });
    }

    @Override
    public <TReq, TResp, TParams> List<TReq> callFormat(
            AbstractBaseFormatter<TReq, TResp, TParams> formatter,
            List<Msg> msgs,
            Supplier<List<TReq>> formatCall) {
        // format 细节对 LangFuse UI 价值较低，保持透传以避免噪声
        return formatCall.get();
    }

    @Override
    public <TResp> TResp runWithContext(ContextView reactorCtx, Supplier<TResp> inner) {
        Context otelContext = resolveParent(reactorCtx);
        try (Scope scope = otelContext.makeCurrent()) {
            return inner.get();
        }
    }

    @Override
    public void shutdown() {
        if (shutdownHook == null) {
            return;
        }
        try {
            shutdownHook.close();
        } catch (Exception e) {
            log.warn("GenAiOtelTracer shutdown failed: {}", e.getMessage());
        }
    }

    private Context resolveParent(ContextView ctxView) {
        return ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                ctxView, Context.current());
    }

    private void applyGenerateOptions(SpanBuilder builder, GenerateOptions options) {
        if (options == null) {
            return;
        }
        if (options.getTemperature() != null) {
            builder.setAttribute(GEN_AI_REQUEST_TEMPERATURE, options.getTemperature());
        }
        if (options.getTopP() != null) {
            builder.setAttribute(GEN_AI_REQUEST_TOP_P, options.getTopP());
        }
        if (options.getMaxTokens() != null) {
            builder.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, options.getMaxTokens().longValue());
        }
    }

    private void applyModelResponse(Span span, ChatResponse response) {
        if (response == null) {
            return;
        }
        putIfPresent(span, GEN_AI_RESPONSE_ID, response.getId());
        putIfPresent(span, GEN_AI_RESPONSE_FINISH_REASONS, response.getFinishReason());
        putIfPresent(span, GEN_AI_OUTPUT_MESSAGES, GenAiAttributeHelper.outputMessagesJson(response));
        ChatUsage usage = response.getUsage();
        if (usage != null) {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, (long) usage.getInputTokens());
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, (long) usage.getOutputTokens());
        }
    }

    private void putConversationId(Span span) {
        String sessionId = TraceContext.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            span.setAttribute(GEN_AI_CONVERSATION_ID, sessionId);
        }
    }

    private void putConversationId(SpanBuilder builder) {
        String sessionId = TraceContext.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            builder.setAttribute(GEN_AI_CONVERSATION_ID, sessionId);
        }
    }

    private static void putIfPresent(Span span, AttributeKey<String> key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }

    private static void putIfPresent(SpanBuilder builder, AttributeKey<String> key, String value) {
        if (value != null && !value.isBlank()) {
            builder.setAttribute(key, value);
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static String safeName(String name) {
        return name != null && !name.isBlank() ? name : "agent";
    }
}
