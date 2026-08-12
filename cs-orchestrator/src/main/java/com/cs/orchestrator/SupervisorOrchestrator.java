package com.cs.orchestrator;

import com.cs.agents.RouterAgent;
import com.cs.agents.aftersales.AfterSalesAgent;
import com.cs.agents.chitchat.ChitChatAgent;
import com.cs.agents.human.HumanCollabAgent;
import com.cs.agents.knowledge.KnowledgeAgent;
import com.cs.agents.order.OrderAgent;
import com.cs.common.enums.IntentType;
import com.cs.common.enums.PendingActionStatus;
import com.cs.common.enums.SessionStatus;
import com.cs.common.model.AgentHandleResult;
import com.cs.common.model.AgentTransitionLog;
import com.cs.common.model.ChatMessage;
import com.cs.common.model.ChatSession;
import com.cs.common.model.HandoffRecord;
import com.cs.common.model.PendingAction;
import com.cs.common.model.RoutingDecision;
import com.cs.common.model.StreamEvent;
import com.cs.common.util.JsonUtils;
import com.cs.infra.observability.LangFuseTracer;
import com.cs.infra.observability.TraceContext;
import com.cs.infra.persistence.ConversationPersistenceService;
import com.cs.knowledge.hook.KnowledgeRAGHook;
import com.cs.memory.shortterm.ShortTermMemoryManager;
import com.cs.tools.handoff.HumanHandoffTool;
import com.cs.tools.permission.ConfirmedToolExecutor;
import com.cs.tools.permission.PendingActionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Supervisor 编排中枢（MVP）：单轮用户消息的完整控制面。
 * <p>
 * <b>主链路</b>：短记忆落库 → sticky/CONFIRM 状态机 → {@link RouterAgent} 意图 →
 * 应用层记忆/RAG 注入 → 领域 Agent（多为 AgentScope {@code ReActAgent}）→
 * SSE {@link StreamEvent} → LangFuse Trace + PG 审计。
 * <p>
 * <b>关键设计</b>：
 * <ul>
 *   <li>短期会话上下文：各 ReActAgent 的 {@code AgentStateStore}（同 session 自动续聊）</li>
 *   <li>长期记忆：各 ReActAgent 原生 {@code LongTermMemory}（STATIC_CONTROL）</li>
 *   <li>RAG 由编排器显式调用（对齐 AS 废弃 GenericRAGHook，不挂到 ReActAgent）</li>
 *   <li>写操作 CONFIRM：{@link PendingActionStore} + {@link ConfirmedToolExecutor}，不恢复 ReAct</li>
 *   <li>sticky：空闲 {@link #STICKY_IDLE} 内优先沿用 {@code session.activeAgent}</li>
 *   <li>阻塞 I/O（Agent/JDBC）在 {@code boundedElastic}，Sink 保证 SSE 订阅前不丢事件</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupervisorOrchestrator {

    private static final Duration STICKY_IDLE = Duration.ofMinutes(15);

    private final RouterAgent routerAgent;
    private final OrderAgent orderAgent;
    private final AfterSalesAgent afterSalesAgent;
    private final KnowledgeAgent knowledgeAgent;
    private final HumanCollabAgent humanCollabAgent;
    private final ChitChatAgent chitChatAgent;

    private final ShortTermMemoryManager shortTermMemory;
    /**
     * 编排层审计用短期消息缓冲（Redis）；Agent 会话上下文由 AgentScope {@code AgentStateStore} 负责。
     * 长期记忆已挂到各 ReActAgent 的原生 {@code LongTermMemory}，此处不再手动 before/after。
     */
    private final KnowledgeRAGHook knowledgeRAGHook;
    private final LangFuseTracer tracer;
    private final PendingActionStore pendingActionStore;
    private final ConfirmedToolExecutor confirmedToolExecutor;
    private final HumanHandoffTool humanHandoffTool;
    private final ConversationPersistenceService persistence;

    private final List<AgentTransitionLog> transitionLogs = new CopyOnWriteArrayList<>();

    public Flux<StreamEvent> processMessage(ChatSession session, String userMessage) {
        // unicast：缓冲直至 HTTP SSE 订阅者挂上；multicast 在 0 subscriber 时会静默丢事件
        // ChitChat 等同步路径极快，原先 fire-and-forget 会在 WebFlux subscribe 前就把事件发完并 complete
        Sinks.Many<StreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        return sink.asFlux()
                .doOnSubscribe(sub -> Mono.fromRunnable(() -> runPipeline(session, userMessage, sink))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                unused -> {},
                                err -> {
                                    log.error("Orchestration failed: {}", err.getMessage(), err);
                                    sink.tryEmitNext(StreamEvent.error("处理您的请求时出现错误，请稍后重试"));
                                    sink.tryEmitNext(StreamEvent.done(session.getSessionId()));
                                    sink.tryEmitComplete();
                                }));
    }

    public Flux<StreamEvent> processConfirmation(ChatSession session, String confirmationId, String decision) {
        Sinks.Many<StreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        return sink.asFlux()
                .doOnSubscribe(sub -> Mono.fromRunnable(() -> {
                    try {
                        PendingAction action = pendingActionStore.findById(confirmationId)
                                .orElseThrow(() -> new IllegalArgumentException("确认单不存在"));
                        if (!session.getSessionId().equals(action.getSessionId())) {
                            throw new IllegalArgumentException("确认单与会话不匹配");
                        }
                        String reply;
                        if ("APPROVE".equalsIgnoreCase(decision) || "确认".equals(decision)) {
                            reply = confirmedToolExecutor.approve(action);
                        } else {
                            reply = confirmedToolExecutor.reject(action);
                        }
                        session.setStatus(SessionStatus.ACTIVE);
                        ChatMessage assistant = ChatMessage.assistantMsg(
                                session.getSessionId(), reply,
                                session.getActiveAgent() != null ? session.getActiveAgent() : "system");
                        shortTermMemory.addMessage(session.getSessionId(), assistant);
                        persistence.saveMessage(assistant);
                        persistence.upsertSession(session);
                        streamResponse(sink, reply,
                                session.getActiveAgent() != null ? session.getActiveAgent() : "system",
                                session.getSessionId());
                    } catch (Exception e) {
                        log.error("Confirmation failed: {}", e.getMessage(), e);
                        sink.tryEmitNext(StreamEvent.error(e.getMessage()));
                        sink.tryEmitNext(StreamEvent.done(session.getSessionId()));
                    } finally {
                        sink.tryEmitComplete();
                    }
                }).subscribeOn(Schedulers.boundedElastic()).subscribe());
    }

    private void runPipeline(ChatSession session, String userMessage, Sinks.Many<StreamEvent> sink) {
        String sessionId = session.getSessionId();
        TraceContext.setSessionId(sessionId);
        TraceContext.setUserId(session.getUserId());
        try {
            if (session.getStatus() == SessionStatus.CLOSED || session.getStatus() == SessionStatus.PAUSED) {
                streamResponse(sink, "会话已结束或暂停，请重新创建会话。", "system", sessionId);
                return;
            }
            if (session.getStatus() == SessionStatus.QUEUED) {
                if (userMessage.contains("取消排队")) {
                    session.setStatus(SessionStatus.ACTIVE);
                    persistence.upsertSession(session);
                    streamResponse(sink, "已取消排队，AI 客服继续为您服务。", "system", sessionId);
                } else {
                    streamResponse(sink, "您已在人工客服排队中，请稍候。如需取消排队请说明「取消排队」。", "system", sessionId);
                }
                return;
            }
            if (session.getStatus() == SessionStatus.HUMAN_ACTIVE) {
                streamResponse(sink, "当前由人工客服为您服务，AI 暂不回复业务问题。", "system", sessionId);
                return;
            }
            if (session.getStatus() == SessionStatus.WAITING_CONFIRM) {
                handleWaitingConfirm(session, userMessage, sink);
                return;
            }

            tracer.startTrace(sessionId, session.getUserId(),
                    Map.of("message", userMessage, "channel", session.getChannel()));

            ChatMessage userMsg = ChatMessage.userMsg(sessionId, userMessage);
            shortTermMemory.addMessage(sessionId, userMsg);
            persistence.saveMessage(userMsg);

            IntentType intent = resolveIntent(session, userMessage, sink);
            session.setActiveAgent(intent.getCode());
            session.setContextVar("intent", intent.getCode());
            session.touch();
            persistence.upsertSession(session);

            // RAG 仅知识意图；长期记忆由 AgentScope LongTermMemory 在 Agent 内自动注入
            String ragContext = intent == IntentType.KNOWLEDGE
                    ? knowledgeRAGHook.beforeReasoning(userMessage, 5) : "";

            AgentHandleResult result = dispatchToAgent(intent, userMessage, ragContext, session);

            if (result.hasPending()) {
                session.setStatus(SessionStatus.WAITING_CONFIRM);
                persistence.upsertSession(session);
                PendingAction pending = result.getPendingAction();
                String payload = JsonUtils.toJson(Map.of(
                        "confirmationId", pending.getConfirmationId(),
                        "action", pending.getToolName(),
                        "argsSummary", pending.getArgsSummary() != null ? pending.getArgsSummary() : "",
                        "expiresAt", pending.getExpiresAt().toString()));
                sink.tryEmitNext(StreamEvent.agentStart(intent.getCode()));
                sink.tryEmitNext(StreamEvent.confirmation(payload, intent.getCode()));
                ChatMessage assistant = ChatMessage.assistantMsg(sessionId, result.getReplyText(), intent.getCode());
                shortTermMemory.addMessage(sessionId, assistant);
                persistence.saveMessage(assistant);
                streamResponse(sink, result.getReplyText(), intent.getCode(), sessionId);
                tracer.endTrace(sessionId, Map.of("pending", pending.getConfirmationId()));
                return;
            }

            if (result.isHandoffRequested()) {
                applyHandoff(session, userMessage,
                        shortTermMemory.getRecentContext(sessionId, 3), result, sink, intent);
                return;
            }

            String agentResponse = result.getReplyText() != null ? result.getReplyText() : "";
            ChatMessage assistant = ChatMessage.assistantMsg(sessionId, agentResponse, intent.getCode());
            shortTermMemory.addMessage(sessionId, assistant);
            persistence.saveMessage(assistant);
            // 长期记忆由 Agent 内 LongTermMemory.record 异步写入，无需编排器再调
            sink.tryEmitNext(StreamEvent.agentStart(intent.getCode()));
            streamResponse(sink, agentResponse, intent.getCode(), sessionId);
            tracer.endTrace(sessionId, Map.of("response", agentResponse, "intent", intent.getCode()));
        } catch (Exception e) {
            log.error("Orchestration error: sessionId={}, error={}", sessionId, e.getMessage(), e);
            sink.tryEmitNext(StreamEvent.error("处理您的请求时出现错误，请稍后重试"));
            tracer.endTraceWithError(sessionId, e.getMessage());
            sink.tryEmitNext(StreamEvent.done(sessionId));
        } finally {
            TraceContext.clear();
            sink.tryEmitComplete();
        }
    }

    private void handleWaitingConfirm(ChatSession session, String userMessage, Sinks.Many<StreamEvent> sink) {
        String sessionId = session.getSessionId();
        PendingAction pending = pendingActionStore.findBySession(sessionId).orElse(null);
        if (pending == null || pending.getStatus() != PendingActionStatus.PENDING) {
            session.setStatus(SessionStatus.ACTIVE);
            persistence.upsertSession(session);
            streamResponse(sink, "待确认操作已失效，请重新发起。", "system", sessionId);
            return;
        }

        String msg = userMessage.trim();
        if (isApproveText(msg)) {
            String reply = confirmedToolExecutor.approve(pending);
            session.setStatus(SessionStatus.ACTIVE);
            persistence.upsertSession(session);
            ChatMessage assistant = ChatMessage.assistantMsg(sessionId, reply,
                    session.getActiveAgent() != null ? session.getActiveAgent() : "system");
            shortTermMemory.addMessage(sessionId, assistant);
            persistence.saveMessage(assistant);
            streamResponse(sink, reply, session.getActiveAgent(), sessionId);
            return;
        }
        if (isRejectOrSwitchText(msg)) {
            String reply = confirmedToolExecutor.reject(pending);
            session.setStatus(SessionStatus.ACTIVE);
            if (isSwitchTopic(msg)) {
                session.setActiveAgent(null);
                persistence.upsertSession(session);
                ChatMessage cancelMsg = ChatMessage.assistantMsg(sessionId, reply, "system");
                shortTermMemory.addMessage(sessionId, cancelMsg);
                persistence.saveMessage(cancelMsg);
                sink.tryEmitNext(StreamEvent.token(reply + "\n", "system"));
                continueAfterCancel(session, userMessage, sink);
                return;
            }
            persistence.upsertSession(session);
            streamResponse(sink, reply, "system", sessionId);
            return;
        }

        streamResponse(sink,
                "您有待确认的操作（" + pending.getArgsSummary() + "）。请先回复「确认」或「取消」，或调用确认接口。",
                "system", sessionId);
    }

    private void continueAfterCancel(ChatSession session, String userMessage, Sinks.Many<StreamEvent> sink) {
        ChatMessage userMsg = ChatMessage.userMsg(session.getSessionId(), userMessage);
        shortTermMemory.addMessage(session.getSessionId(), userMsg);
        persistence.saveMessage(userMsg);
        IntentType intent = resolveIntent(session, userMessage, sink);
        session.setActiveAgent(intent.getCode());
        session.touch();
        persistence.upsertSession(session);
        // 取消确认后续跑：RAG（如需）后派发；记忆由 AgentScope 组件负责
        String ragContext = intent == IntentType.KNOWLEDGE
                ? knowledgeRAGHook.beforeReasoning(userMessage, 5) : "";
        AgentHandleResult result = dispatchToAgent(intent, userMessage, ragContext, session);
        if (result.hasPending()) {
            session.setStatus(SessionStatus.WAITING_CONFIRM);
            persistence.upsertSession(session);
            PendingAction pending = result.getPendingAction();
            String payload = JsonUtils.toJson(Map.of(
                    "confirmationId", pending.getConfirmationId(),
                    "action", pending.getToolName(),
                    "argsSummary", pending.getArgsSummary() != null ? pending.getArgsSummary() : "",
                    "expiresAt", pending.getExpiresAt().toString()));
            sink.tryEmitNext(StreamEvent.confirmation(payload, intent.getCode()));
            streamResponse(sink, result.getReplyText(), intent.getCode(), session.getSessionId());
            return;
        }
        if (result.isHandoffRequested()) {
            applyHandoff(session, userMessage,
                    shortTermMemory.getRecentContext(session.getSessionId(), 3), result, sink, intent);
            return;
        }
        String agentResponse = result.getReplyText() != null ? result.getReplyText() : "";
        ChatMessage assistant = ChatMessage.assistantMsg(session.getSessionId(), agentResponse, intent.getCode());
        shortTermMemory.addMessage(session.getSessionId(), assistant);
        persistence.saveMessage(assistant);
        streamResponse(sink, agentResponse, intent.getCode(), session.getSessionId());
    }

    private void applyHandoff(ChatSession session, String userMessage, String memoryContext,
                              AgentHandleResult result, Sinks.Many<StreamEvent> sink, IntentType intent) {
        HandoffRecord record = result.getHandoffRecord();
        if (record == null) {
            record = humanHandoffTool.enqueue(
                    session.getSessionId(),
                    session.getTenantId(),
                    session.getUserId(),
                    intent == IntentType.HUMAN_SERVICE ? "用户主动要求" : "系统升级",
                    "NORMAL",
                    memoryContext != null && !memoryContext.isBlank() ? memoryContext : userMessage,
                    Map.of("intent", intent.getCode()));
        }
        String reply = result.getReplyText() != null ? result.getReplyText()
                : "已为您转接人工客服，请稍候。";
        session.setStatus(SessionStatus.QUEUED);
        session.setContextVar("handoffId", record.getId());
        persistence.upsertSession(session);
        String queuePayload = JsonUtils.toJson(Map.of(
                "handoffId", record.getId(),
                "skillGroup", record.getSkillGroup(),
                "status", record.getStatus().name()));
        sink.tryEmitNext(StreamEvent.queueUpdate(queuePayload));
        ChatMessage assistant = ChatMessage.assistantMsg(session.getSessionId(), reply, intent.getCode());
        shortTermMemory.addMessage(session.getSessionId(), assistant);
        persistence.saveMessage(assistant);
        streamResponse(sink, reply, intent.getCode(), session.getSessionId());
        tracer.endTrace(session.getSessionId(), Map.of("handoffId", record.getId()));
    }

    private IntentType resolveIntent(ChatSession session, String userMessage, Sinks.Many<StreamEvent> sink) {
        String active = session.getActiveAgent();
        boolean hasSticky = active != null && !active.isBlank()
                && !"router".equals(active) && !"system".equals(active);

        if (hasSticky && !shouldForceReroute(session, userMessage)) {
            IntentType stickyIntent = IntentType.fromCode(active);
            logTransition(session, active, stickyIntent.getCode(), "STICKY_SKIP", null);
            log.info("Sticky agent: {}", stickyIntent);
            return stickyIntent;
        }

        sink.tryEmitNext(StreamEvent.agentStart("RouterAgent"));
        String routingSpanId = tracer.startRoutingSpan(session.getSessionId(),
                Map.of("message", userMessage));
        RoutingDecision decision = routerAgent.route(userMessage);
        tracer.endSpan(routingSpanId,
                Map.of("intent", decision.getIntent().getCode(),
                        "confidence", decision.getConfidence()),
                null);
        sink.tryEmitNext(StreamEvent.agentEnd("RouterAgent"));

        IntentType intent = decision.isLowConfidence() ? IntentType.CHITCHAT : decision.getIntent();
        logTransition(session, active, intent.getCode(), "RE_ROUTE", decision);
        if (decision.getEntities() != null) {
            decision.getEntities().forEach(session::setContextVar);
        }
        return intent;
    }

    private boolean shouldForceReroute(ChatSession session, String userMessage) {
        String msg = userMessage.toLowerCase();
        if (msg.contains("转人工") || msg.contains("人工客服") || msg.contains("真人")) {
            return true;
        }
        if (msg.contains("换个问题") || msg.contains("换个话题") || msg.contains("我还想问")) {
            return true;
        }
        if (session.getLastActiveAt() != null
                && Duration.between(session.getLastActiveAt(), Instant.now()).compareTo(STICKY_IDLE) > 0) {
            return true;
        }
        // 命中明确业务关键词且目标意图与当前 sticky Agent 不同 → 强制重路由
        // 例：闲聊后问「保修」应从 chitchat 切到 knowledge
        String active = session.getActiveAgent();
        IntentType hinted = routerAgent.hintIntent(userMessage);
        if (hinted != IntentType.CHITCHAT && active != null && !hinted.getCode().equals(active)) {
            log.info("Force re-route by business keyword: active={}, hinted={}, msg={}",
                    active, hinted.getCode(),
                    userMessage.substring(0, Math.min(40, userMessage.length())));
            return true;
        }
        return false;
    }

    private AgentHandleResult dispatchToAgent(IntentType intent, String userMessage,
                                              String ragContext, ChatSession session) {
        String sessionId = session.getSessionId();
        String spanId = tracer.startAgentSpan(sessionId, intent.getCode(),
                Map.of("message", userMessage), null);
        TraceContext.setParentSpanId(spanId);
        try {
            // 业务补充上下文可为空；跨轮对话与长期记忆由 AgentScope StateStore / LongTermMemory 注入
            String extra = "";
            AgentHandleResult result = switch (intent) {
                case ORDER -> AgentHandleResult.text(orderAgent.handle(userMessage, extra));
                case AFTER_SALES -> afterSalesAgent.handle(userMessage, extra,
                        sessionId, session.getUserId(), session.getTenantId());
                case KNOWLEDGE, PRE_SALES -> AgentHandleResult.text(
                        knowledgeAgent.handle(userMessage, ragContext));
                case HUMAN_SERVICE, COMPLAINT -> humanCollabAgent.handle(userMessage, extra,
                        sessionId, session.getUserId(), session.getTenantId());
                case CHITCHAT, RISK_CONTROL -> AgentHandleResult.text(
                        chitChatAgent.handle(userMessage, extra));
            };
            tracer.endSpan(spanId, Map.of("response",
                    result.getReplyText() != null ? result.getReplyText() : ""), null);
            return result;
        } catch (Exception e) {
            tracer.endSpanWithError(spanId, e.getMessage());
            return AgentHandleResult.text("抱歉，处理您的请求时遇到了问题。请稍后重试或转接人工客服。");
        } finally {
            TraceContext.setParentSpanId(null);
        }
    }

    private void logTransition(ChatSession session, String from, String to, String trigger,
                               RoutingDecision decision) {
        AgentTransitionLog logEntry = AgentTransitionLog.builder()
                .sessionId(session.getSessionId())
                .fromAgent(from)
                .toAgent(to)
                .trigger(trigger)
                .routingSnapshot(decision)
                .at(Instant.now())
                .build();
        transitionLogs.add(logEntry);
        if (transitionLogs.size() > 1000) {
            transitionLogs.subList(0, 200).clear();
        }
        log.debug("Agent transition: {} -> {} ({})", from, to, trigger);
    }

    public List<AgentTransitionLog> recentTransitions() {
        return new ArrayList<>(transitionLogs);
    }

    private void streamResponse(Sinks.Many<StreamEvent> sink, String response, String agentName, String sessionId) {
        if (response == null) {
            response = "";
        }
        String[] segments = response.split("(?<=[。！？\n])");
        for (String segment : segments) {
            if (!segment.isBlank()) {
                sink.tryEmitNext(StreamEvent.token(segment, agentName));
            }
        }
        sink.tryEmitNext(StreamEvent.agentEnd(agentName));
        sink.tryEmitNext(StreamEvent.done(sessionId));
    }

    private boolean isApproveText(String msg) {
        return msg.equals("确认") || msg.equalsIgnoreCase("yes") || msg.equalsIgnoreCase("approve")
                || msg.equals("是的") || msg.equals("同意") || msg.equals("好的确认");
    }

    private boolean isRejectOrSwitchText(String msg) {
        return msg.contains("取消") || msg.contains("算了") || msg.contains("不要了")
                || isSwitchTopic(msg) || msg.equalsIgnoreCase("reject");
    }

    private boolean isSwitchTopic(String msg) {
        return msg.contains("我问别的") || msg.contains("换个问题") || msg.contains("另外问");
    }
}
