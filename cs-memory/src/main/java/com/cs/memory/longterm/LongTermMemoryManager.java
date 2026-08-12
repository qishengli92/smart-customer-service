package com.cs.memory.longterm;

import com.cs.infra.config.MilvusProperties;
import com.cs.infra.embedding.DashScopeEmbeddingService;
import com.cs.infra.observability.TraceContext;
import com.cs.memory.milvus.MilvusMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 跨会话长期记忆存储：Embedding + Milvus；Milvus 不可用时回退进程内列表。
 * <p>
 * 供 AgentScope 原生 {@link io.agentscope.core.memory.LongTermMemory} 适配器调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LongTermMemoryManager {

    private final MilvusProperties milvusProperties;
    private final DashScopeEmbeddingService embeddingService;
    private final MilvusMemoryStore milvusMemoryStore;

    /** fallback: userId -> records */
    private final Map<String, List<MemoryRecord>> localFallback = new ConcurrentHashMap<>();

    public void store(String userId, String type, String content, Map<String, String> metadata) {
        if (userId == null || userId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        String text = content.length() > 2000 ? content.substring(0, 2000) : content;
        try {
            float[] vector = embeddingService.embed(text, milvusProperties.getEmbeddingDimension());
            MemoryRecord record = MemoryRecord.builder()
                    .recordId("mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                    .userId(userId)
                    .type(type != null ? type : "interaction")
                    .content(text)
                    .metadata(metadata)
                    .createdAt(Instant.now())
                    .build();

            if (milvusMemoryStore.isAvailable()) {
                milvusMemoryStore.insert(record, vector);
            } else {
                localFallback.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(record);
                trimLocal(userId);
            }
            log.info("Long-term memory stored: userId={}, type={}, len={}",
                    userId, type, text.length());
        } catch (Exception e) {
            log.warn("Long-term memory store failed: {}", e.getMessage());
            MemoryRecord record = MemoryRecord.builder()
                    .recordId("mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                    .userId(userId)
                    .type(type != null ? type : "interaction")
                    .content(text)
                    .metadata(metadata)
                    .createdAt(Instant.now())
                    .build();
            localFallback.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(record);
            trimLocal(userId);
        }
    }

    public List<MemoryRecord> search(String userId, String query, int topK) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        try {
            float[] vector = embeddingService.embed(query, milvusProperties.getEmbeddingDimension());
            if (milvusMemoryStore.isAvailable()) {
                return milvusMemoryStore.search(
                        userId, vector, topK, milvusProperties.getSimilarityThreshold());
            }
        } catch (Exception e) {
            log.warn("Long-term memory search failed, use local: {}", e.getMessage());
        }
        return localSearch(userId, topK);
    }

    public String getUserProfileSummary(String userId) {
        String uid = userId != null && !userId.isBlank() ? userId : TraceContext.getUserId();
        if (uid == null || uid.isBlank()) {
            return "";
        }
        List<MemoryRecord> records = search(uid, "用户偏好 画像 历史交互", 5);
        if (records.isEmpty()) {
            records = localSearch(uid, 5);
        }
        if (records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("用户长期记忆：\n");
        for (MemoryRecord record : records) {
            sb.append("- [").append(record.getType()).append("] ")
                    .append(record.getContent()).append("\n");
        }
        return sb.toString();
    }

    private List<MemoryRecord> localSearch(String userId, int topK) {
        List<MemoryRecord> all = localFallback.getOrDefault(userId, List.of());
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        int from = Math.max(0, all.size() - topK);
        return List.copyOf(all.subList(from, all.size()));
    }

    private void trimLocal(String userId) {
        List<MemoryRecord> list = localFallback.get(userId);
        if (list != null && list.size() > 100) {
            list.subList(0, list.size() - 100).clear();
        }
    }
}
