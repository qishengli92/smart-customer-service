package com.cs.knowledge.hook;

import com.cs.knowledge.retrieval.KnowledgeChunk;
import com.cs.knowledge.retrieval.KnowledgeRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用层 RAG 检索：在 Agent 推理前注入知识片段。
 * <p>
 * <b>与 AgentScope 的对应关系（概念对齐，非框架 API）：</b>
 * <ul>
 *   <li>行为类似 v1 的 {@code GenericRAGHook}（PreReasoning 前自动检索并注入）</li>
 *   <li>知识源类似 v1 的 {@code Knowledge} / {@code KnowledgeRetrievalTools}</li>
 *   <li>上述 API 在 AgentScope 2.0 已 {@code @Deprecated}，官方建议应用层自管检索；
 *       本类即该策略的落地，不依赖废弃的 Hook / RAG 包</li>
 *   <li>若日后框架化，优先做成 {@code MiddlewareBase#onSystemPrompt} / {@code onAgent}，
 *       勿再接入 {@code GenericRAGHook}</li>
 * </ul>
 * 当前由编排器 / KnowledgeAgent 显式调用 {@link #beforeReasoning}，而非挂到 ReActAgent。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRAGHook {

    private final KnowledgeRetrievalService retrievalService;

    /**
     * 推理前检索（对齐 GenericRAGHook 的「自动注入」时机）。
     *
     * @param userMsg 用户消息
     * @param topK    检索数量
     * @return 格式化的知识上下文；失败时返回空串
     */
    public String beforeReasoning(String userMsg, int topK) {
        try {
            List<KnowledgeChunk> chunks = retrievalService.retrieveFromAll(
                    userMsg, topK, 0.7);
            String context = retrievalService.formatAsContext(chunks);
            log.debug("RAGHook injected {} knowledge chunks", chunks.size());
            return context;
        } catch (Exception e) {
            log.warn("RAGHook retrieval failed: {}", e.getMessage());
            return "";
        }
    }
}
