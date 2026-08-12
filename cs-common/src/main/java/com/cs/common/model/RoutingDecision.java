package com.cs.common.model;

import com.cs.common.enums.IntentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 路由决策模型 - Router Agent 输出的结构化结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingDecision {

    /**
     * 主意图分类
     */
    private IntentType intent;

    /**
     * 子意图（如 REFUND / RETURN / QUERY 等）
     */
    private String subIntent;

    /**
     * 分类置信度 [0, 1]
     */
    private Double confidence;

    /**
     * 提取的实体信息
     */
    private Map<String, String> entities;

    /**
     * 路由理由
     */
    private String reason;

    /**
     * 判断置信度是否过低，需要走兜底逻辑
     */
    public boolean isLowConfidence() {
        return confidence != null && confidence < 0.6;
    }
}
