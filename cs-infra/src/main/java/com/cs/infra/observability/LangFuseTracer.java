package com.cs.infra.observability;

import com.cs.common.util.IdGenerator;
import com.cs.infra.config.LangFuseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangFuse 可观测门面：编排层 Trace/Span 树 + HTTP ingestion。
 * <p>
 * 与 {@link com.cs.infra.agentscope.LangFuseAgentMiddleware} /
 * {@link com.cs.infra.observability.otel.GenAiOtelTracer} 分工：
 * 本类管会话级 Trace（Track A ingestion）；LLM/Tool 优先走 OTLP GenAI（Track B）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangFuseTracer {

    private final LangFuseProperties properties;
    private final Map<String, TraceRecord> activeTraces = new ConcurrentHashMap<>();
    private final Map<String, TraceSpan> activeSpans = new ConcurrentHashMap<>();
    private final WebClient.Builder webClientBuilder = WebClient.builder();

    public String startTrace(String sessionId, String userId, Map<String, Object> input) {
        String traceId = IdGenerator.traceId();
        TraceRecord trace = TraceRecord.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .name("customer_service_chat")
                .input(input)
                .startTime(Instant.now())
                .status("running")
                .build();
        activeTraces.put(sessionId, trace);
        log.debug("Trace started: traceId={}, sessionId={}", traceId, sessionId);
        return traceId;
    }

    public void endTrace(String sessionId, Map<String, Object> output) {
        TraceRecord trace = activeTraces.get(sessionId);
        if (trace != null) {
            trace.setEndTime(Instant.now());
            trace.setOutput(output);
            trace.setStatus("ok");
            calculateTotalTokenUsage(trace);
            flushToLangFuse(trace);
            activeTraces.remove(sessionId);
            log.debug("Trace ended: traceId={}, duration={}ms, totalTokens={}",
                    trace.getTraceId(), trace.getDurationMs(),
                    trace.getTotalTokenUsage() != null ? trace.getTotalTokenUsage().getTotalTokens() : 0);
        }
    }

    public void endTraceWithError(String sessionId, String errorMessage) {
        TraceRecord trace = activeTraces.get(sessionId);
        if (trace != null) {
            trace.setEndTime(Instant.now());
            trace.setOutput(Map.of("error", errorMessage));
            trace.setStatus("error");
            calculateTotalTokenUsage(trace);
            flushToLangFuse(trace);
            activeTraces.remove(sessionId);
            log.warn("Trace ended with error: traceId={}, error={}",
                    trace.getTraceId(), errorMessage);
        }
    }

    public String startAgentSpan(String sessionId, String agentName,
                                 Map<String, Object> input, String parentSpanId) {
        String spanId = "span_" + IdGenerator.messageId();
        TraceSpan span = TraceSpan.builder()
                .spanId(spanId)
                .traceId(getTraceId(sessionId))
                .parentSpanId(parentSpanId)
                .type("agent")
                .name(agentName)
                .input(input)
                .startTime(Instant.now())
                .status("running")
                .build();
        activeSpans.put(spanId, span);
        TraceRecord trace = activeTraces.get(sessionId);
        if (trace != null) {
            trace.addSpan(span);
        }
        log.debug("Agent span started: spanId={}, agent={}", spanId, agentName);
        return spanId;
    }

    public String startToolSpan(String sessionId, String toolName,
                                Map<String, Object> input, String parentSpanId) {
        String spanId = "span_" + IdGenerator.messageId();
        TraceSpan span = TraceSpan.builder()
                .spanId(spanId)
                .traceId(getTraceId(sessionId))
                .parentSpanId(parentSpanId)
                .type("tool")
                .name(toolName)
                .input(input)
                .startTime(Instant.now())
                .status("running")
                .build();
        activeSpans.put(spanId, span);
        TraceRecord trace = activeTraces.get(sessionId);
        if (trace != null) {
            trace.addSpan(span);
        }
        return spanId;
    }

    public String startRoutingSpan(String sessionId, Map<String, Object> input) {
        return startAgentSpan(sessionId, "RouterAgent", input, null);
    }

    /**
     * 记录一次大模型 generation（模型名 + Token 用量）
     */
    public void recordGeneration(String sessionId, String parentSpanId, String name,
                                 String model, Map<String, Object> modelParameters,
                                 Map<String, Object> input, Map<String, Object> output,
                                 TraceSpan.TokenUsage tokenUsage,
                                 Instant startTime, Instant endTime) {
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("Skip generation record: no active session in TraceContext");
            return;
        }
        TraceRecord trace = activeTraces.get(sessionId);
        if (trace == null) {
            log.debug("Skip generation record: no active trace for session={}", sessionId);
            return;
        }
        TraceSpan span = TraceSpan.builder()
                .spanId("gen_" + IdGenerator.messageId())
                .traceId(trace.getTraceId())
                .parentSpanId(parentSpanId)
                .type("generation")
                .name(name != null ? name : "llm-chat")
                .model(model)
                .modelParameters(modelParameters)
                .input(input)
                .output(output)
                .tokenUsage(tokenUsage)
                .startTime(startTime != null ? startTime : Instant.now())
                .endTime(endTime != null ? endTime : Instant.now())
                .status("ok")
                .build();
        trace.addSpan(span);
        log.info("Generation recorded: model={}, promptTokens={}, completionTokens={}, totalTokens={}",
                model,
                tokenUsage != null ? tokenUsage.getPromptTokens() : null,
                tokenUsage != null ? tokenUsage.getCompletionTokens() : null,
                tokenUsage != null ? tokenUsage.getTotalTokens() : null);
    }

    public void endSpan(String spanId, Map<String, Object> output,
                        TraceSpan.TokenUsage tokenUsage) {
        TraceSpan span = activeSpans.remove(spanId);
        if (span != null) {
            span.setEndTime(Instant.now());
            span.setOutput(output);
            span.setTokenUsage(tokenUsage);
            span.setStatus("ok");
        }
    }

    public void endSpanWithError(String spanId, String errorMessage) {
        TraceSpan span = activeSpans.remove(spanId);
        if (span != null) {
            span.setEndTime(Instant.now());
            span.setOutput(Map.of("error", errorMessage));
            span.setStatus("error");
        }
    }

    public TraceRecord getActiveTrace(String sessionId) {
        return activeTraces.get(sessionId);
    }

    private String getTraceId(String sessionId) {
        TraceRecord trace = activeTraces.get(sessionId);
        return trace != null ? trace.getTraceId() : "unknown";
    }

    private void calculateTotalTokenUsage(TraceRecord trace) {
        int prompt = 0, completion = 0, total = 0;
        for (TraceSpan span : trace.getSpans()) {
            if (span.getTokenUsage() != null) {
                prompt += span.getTokenUsage().getPromptTokens() != null
                        ? span.getTokenUsage().getPromptTokens() : 0;
                completion += span.getTokenUsage().getCompletionTokens() != null
                        ? span.getTokenUsage().getCompletionTokens() : 0;
                total += span.getTokenUsage().getTotalTokens() != null
                        ? span.getTokenUsage().getTotalTokens() : 0;
            }
        }
        trace.setTotalTokenUsage(TraceSpan.TokenUsage.builder()
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .build());
    }

    private void flushToLangFuse(TraceRecord trace) {
        int totalTokens = trace.getTotalTokenUsage() != null && trace.getTotalTokenUsage().getTotalTokens() != null
                ? trace.getTotalTokenUsage().getTotalTokens() : 0;
        log.info("LangFuse Trace flushed: traceId={}, sessionId={}, duration={}ms, spans={}, totalTokens={}, status={}",
                trace.getTraceId(), trace.getSessionId(), trace.getDurationMs(),
                trace.getSpans().size(), totalTokens, trace.getStatus());

        if (!properties.isEnabled() || !properties.isFlushEnabled()) {
            return;
        }
        String pk = properties.getPublicKey();
        String sk = properties.getSecretKey();
        if (pk == null || sk == null || pk.contains("your-public") || sk.contains("your-secret")) {
            log.debug("LangFuse keys not configured, skip remote flush");
            return;
        }

        try {
            String auth = Base64.getEncoder().encodeToString(
                    (pk + ":" + sk).getBytes(StandardCharsets.UTF_8));
            List<Map<String, Object>> batch = buildIngestionBatch(trace);

            webClientBuilder.build()
                    .post()
                    .uri(trimSlash(properties.getBaseUrl()) + "/api/public/ingestion")
                    .header("Authorization", "Basic " + auth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("batch", batch))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .subscribe(
                            ok -> log.debug("LangFuse remote flush ok: {} events={}",
                                    trace.getTraceId(), batch.size()),
                            err -> log.warn("LangFuse remote flush failed: {}", err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("LangFuse flush error: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> buildIngestionBatch(TraceRecord trace) {
        List<Map<String, Object>> batch = new ArrayList<>();
        Instant now = Instant.now();

        Map<String, Object> traceMeta = new LinkedHashMap<>();
        traceMeta.put("spanCount", trace.getSpans().size());
        traceMeta.put("status", trace.getStatus());
        traceMeta.put("durationMs", trace.getDurationMs());
        if (trace.getTotalTokenUsage() != null) {
            traceMeta.put("promptTokens", trace.getTotalTokenUsage().getPromptTokens());
            traceMeta.put("completionTokens", trace.getTotalTokenUsage().getCompletionTokens());
            traceMeta.put("totalTokens", trace.getTotalTokenUsage().getTotalTokens());
        }

        batch.add(event("trace-create", now, Map.of(
                "id", trace.getTraceId(),
                "name", trace.getName(),
                "sessionId", nullToEmpty(trace.getSessionId()),
                "userId", nullToEmpty(trace.getUserId()),
                "input", trace.getInput() != null ? trace.getInput() : Map.of(),
                "output", trace.getOutput() != null ? trace.getOutput() : Map.of(),
                "metadata", traceMeta,
                "timestamp", (trace.getStartTime() != null ? trace.getStartTime() : now).toString()
        )));

        for (TraceSpan span : trace.getSpans()) {
            if ("generation".equals(span.getType())) {
                batch.add(event("generation-create", now, toGenerationBody(trace, span)));
            } else {
                batch.add(event("span-create", now, toSpanBody(trace, span)));
            }
        }
        return batch;
    }

    private Map<String, Object> toSpanBody(TraceRecord trace, TraceSpan span) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", span.getSpanId());
        body.put("traceId", trace.getTraceId());
        body.put("name", span.getName());
        body.put("startTime", (span.getStartTime() != null ? span.getStartTime() : Instant.now()).toString());
        if (span.getEndTime() != null) {
            body.put("endTime", span.getEndTime().toString());
        }
        if (span.getParentSpanId() != null) {
            body.put("parentObservationId", span.getParentSpanId());
        }
        body.put("input", span.getInput() != null ? span.getInput() : Map.of());
        body.put("output", span.getOutput() != null ? span.getOutput() : Map.of());
        body.put("metadata", Map.of(
                "type", span.getType() != null ? span.getType() : "span",
                "status", span.getStatus() != null ? span.getStatus() : "ok"
        ));
        return body;
    }

    private Map<String, Object> toGenerationBody(TraceRecord trace, TraceSpan span) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", span.getSpanId());
        body.put("traceId", trace.getTraceId());
        body.put("name", span.getName());
        body.put("startTime", (span.getStartTime() != null ? span.getStartTime() : Instant.now()).toString());
        if (span.getEndTime() != null) {
            body.put("endTime", span.getEndTime().toString());
        }
        if (span.getParentSpanId() != null) {
            body.put("parentObservationId", span.getParentSpanId());
        }
        if (span.getModel() != null) {
            body.put("model", span.getModel());
        }
        if (span.getModelParameters() != null) {
            body.put("modelParameters", span.getModelParameters());
        }
        body.put("input", span.getInput() != null ? span.getInput() : Map.of());
        body.put("output", span.getOutput() != null ? span.getOutput() : Map.of());

        if (span.getTokenUsage() != null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            if (span.getTokenUsage().getPromptTokens() != null) {
                usage.put("input", span.getTokenUsage().getPromptTokens());
                usage.put("promptTokens", span.getTokenUsage().getPromptTokens());
            }
            if (span.getTokenUsage().getCompletionTokens() != null) {
                usage.put("output", span.getTokenUsage().getCompletionTokens());
                usage.put("completionTokens", span.getTokenUsage().getCompletionTokens());
            }
            if (span.getTokenUsage().getTotalTokens() != null) {
                usage.put("total", span.getTokenUsage().getTotalTokens());
                usage.put("totalTokens", span.getTokenUsage().getTotalTokens());
            }
            usage.put("unit", "TOKENS");
            body.put("usage", usage);

            Map<String, Object> usageDetails = new LinkedHashMap<>();
            if (span.getTokenUsage().getPromptTokens() != null) {
                usageDetails.put("input", span.getTokenUsage().getPromptTokens());
            }
            if (span.getTokenUsage().getCompletionTokens() != null) {
                usageDetails.put("output", span.getTokenUsage().getCompletionTokens());
            }
            if (span.getTokenUsage().getTotalTokens() != null) {
                usageDetails.put("total", span.getTokenUsage().getTotalTokens());
            }
            body.put("usageDetails", usageDetails);
        }
        return body;
    }

    private Map<String, Object> event(String type, Instant timestamp, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", IdGenerator.messageId());
        event.put("type", type);
        event.put("timestamp", timestamp.toString());
        event.put("body", body);
        return event;
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://cloud.langfuse.com";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
