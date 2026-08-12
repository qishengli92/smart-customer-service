package com.cs.knowledge.hook;

import com.cs.knowledge.retrieval.KnowledgeChunk;
import com.cs.knowledge.retrieval.KnowledgeRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG Hook - 在 Agent 推理前注入知识检索结果
 * <p>
 * 对应 AgentScope Java 的 RAGHook 机制。
 * 在 Agent 推理前调用知识检索服务，将相关文档片段注入到 Agent 上下文中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRAGHook {

    private final KnowledgeRetrievalService retrievalService;

    /**
     * 在推理前检索知识并返回上下文文本
     *
     * @param userMsg 用户消息
     * @param topK    检索数量
     * @return 格式化的知识上下文
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
