package com.cs.knowledge.hook;

import com.cs.knowledge.retrieval.KnowledgeChunk;
import com.cs.knowledge.retrieval.KnowledgeRetrievalService;
import com.cs.knowledge.retrieval.RagResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用层 RAG 检索：在 Agent 推理前注入知识片段与编号引用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRAGHook {

    private final KnowledgeRetrievalService retrievalService;

    public RagResult retrieve(String userMsg, int topK) {
        try {
            List<KnowledgeChunk> chunks = retrievalService.retrieveFromAll(userMsg, topK, 0.0);
            RagResult result = retrievalService.toRagResult(chunks);
            log.debug("RAGHook retrieved {} knowledge chunks", chunks.size());
            return result;
        } catch (Exception e) {
            log.warn("RAGHook retrieval failed: {}", e.getMessage());
            return RagResult.empty();
        }
    }

    /**
     * 推理前检索（对齐 GenericRAGHook 的「自动注入」时机）。
     */
    public String beforeReasoning(String userMsg, int topK) {
        return retrieve(userMsg, topK).getContext();
    }
}
