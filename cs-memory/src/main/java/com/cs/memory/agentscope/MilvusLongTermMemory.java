package com.cs.memory.agentscope;

import com.cs.infra.observability.TraceContext;
import com.cs.memory.longterm.LongTermMemoryManager;
import com.cs.memory.longterm.MemoryRecord;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AgentScope 原生 {@link LongTermMemory} 适配：跨会话记忆读写。
 * <p>
 * 由 {@code ReActAgent.builder().longTermMemory(this).longTermMemoryMode(STATIC_CONTROL)} 挂载；
 * 框架在推理前 {@link #retrieve}、回复后 {@link #record}。
 * 用户隔离依赖编排线程 {@link TraceContext#getUserId()}（与 RuntimeContext.userId 一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class MilvusLongTermMemory implements LongTermMemory {

    private final LongTermMemoryManager longTermMemoryManager;

    @Override
    public Mono<Void> record(List<Msg> msgs) {
        return Mono.<Void>fromRunnable(() -> {
                    String userId = TraceContext.getUserId();
                    if (userId == null || userId.isBlank() || msgs == null || msgs.isEmpty()) {
                        return;
                    }
                    String content = msgs.stream()
                            .filter(m -> m != null && m.getTextContent() != null
                                    && !m.getTextContent().isBlank())
                            .map(m -> {
                                String role = m.getRole() != null ? m.getRole().name() : "msg";
                                return "[" + role + "] " + m.getTextContent();
                            })
                            .collect(Collectors.joining("\n"));
                    if (content.isBlank()) {
                        return;
                    }
                    if (content.length() > 1500) {
                        content = content.substring(0, 1500) + "...";
                    }
                    longTermMemoryManager.store(userId, "interaction", content,
                            Map.of("source", "agentscope_ltm"));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("LongTermMemory.record failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<String> retrieve(Msg msg) {
        return Mono.fromCallable(() -> {
                    String userId = TraceContext.getUserId();
                    if (userId == null || userId.isBlank()) {
                        return "";
                    }
                    String query = msg != null && msg.getTextContent() != null
                            ? msg.getTextContent() : "用户画像";
                    List<MemoryRecord> records = longTermMemoryManager.search(userId, query, 5);
                    if (records.isEmpty()) {
                        return longTermMemoryManager.getUserProfileSummary(userId);
                    }
                    StringBuilder sb = new StringBuilder("相关长期记忆：\n");
                    for (MemoryRecord r : records) {
                        sb.append("- ").append(r.getContent()).append("\n");
                    }
                    return sb.toString();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("LongTermMemory.retrieve failed: {}", e.getMessage());
                    return Mono.just("");
                });
    }
}
