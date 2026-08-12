package com.cs.memory.longterm;

import com.cs.infra.config.MilvusProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 长期记忆管理器 - 基于 Milvus 向量数据库
 * <p>
 * 存储跨会话的用户画像、历史偏好、售后记录等，
 * 支持语义相似度检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LongTermMemoryManager {

    private final MilvusProperties milvusProperties;

    /**
     * 存储记忆记录
     *
     * @param userId    用户ID
     * @param type      记忆类型
     * @param content   记忆内容
     * @param metadata  元数据
     */
    public void store(String userId, String type, String content,
                       java.util.Map<String, String> metadata) {
        // TODO: 实际调用 Milvus SDK 写入
        // 1. 调用 DashScope Embedding API 生成向量
        // 2. 构建Milvus InsertParam
        // 3. 执行插入
        log.info("Long-term memory stored: userId={}, type={}, content={}",
                userId, type, content.substring(0, Math.min(50, content.length())));
    }

    /**
     * 语义检索记忆
     *
     * @param userId      用户ID
     * @param query       查询文本
     * @param topK        返回数量
     * @return 相关记忆列表
     */
    public List<MemoryRecord> search(String userId, String query, int topK) {
        // TODO: 实际调用 Milvus SDK 检索
        // 1. 调用 DashScope Embedding API 生成查询向量
        // 2. 构建Milvus SearchParam
        // 3. 执行搜索，解析结果
        log.info("Long-term memory search: userId={}, query={}, topK={}",
                userId, query.substring(0, Math.min(30, query.length())), topK);
        return Collections.emptyList();
    }

    /**
     * 获取用户画像摘要
     */
    public String getUserProfileSummary(String userId) {
        List<MemoryRecord> records = search(userId, "用户画像基本信息", 5);
        if (records.isEmpty()) {
            return "暂无用户历史记录";
        }
        StringBuilder sb = new StringBuilder("用户历史记录：\n");
        for (MemoryRecord record : records) {
            sb.append("- ").append(record.getContent()).append("\n");
        }
        return sb.toString();
    }
}
