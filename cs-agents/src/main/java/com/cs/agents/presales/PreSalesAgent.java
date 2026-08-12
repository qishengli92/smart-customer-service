package com.cs.agents.presales;

import com.cs.common.model.ProductInfo;
import com.cs.tools.product.ProductQueryTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 售前咨询 Agent（MVP 简化逻辑，尚未接入 AgentScope {@code ReActAgent}）。
 * <p>
 * 当前 Router 将 {@code PRE_SALES} 映射到 {@code KNOWLEDGE}，本类多为预留；
 * 产品数据来自 {@link ProductQueryTool} Mock，后续应改为 Toolkit + ReAct。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreSalesAgent {

    private final ProductQueryTool productQueryTool;

    private static final String SYSTEM_PROMPT = """
            你是专业的售前顾问。根据用户需求推荐合适的产品方案。
            工作流程：
            1. 理解用户需求场景和预算
            2. 查询产品信息
            3. 如需对比，进行产品对比
            4. 给出专业推荐和理由
            注意：不要编造产品信息，只使用工具查询到的真实数据。
            如果查询不到产品，请坦诚告知。
            """;

    /**
     * 处理售前咨询
     *
     * @param userMessage 用户消息
     * @param context     上下文（可选 RAG 注入的知识）
     * @return Agent 回复
     */
    public String handle(String userMessage, String context) {
        log.info("PreSalesAgent handling: {}", userMessage.substring(0, Math.min(50, userMessage.length())));

        // ReAct Loop 模拟：推理 → 行动 → 观察
        // Step 1: 检查是否包含产品关键词，尝试查询
        // MVP 阶段使用简化逻辑，后续替换为 AgentScope ReActAgent

        ProductInfo product = tryFindProduct(userMessage);
        if (product != null) {
            return formatProductRecommendation(product, userMessage);
        }

        // 没有找到具体产品，给出通用引导
        return "我是您的售前顾问，可以帮您推荐合适的产品。请告诉我您感兴趣的产品类型或使用场景，" +
                "比如智能穿戴、音频设备或充电配件，我来为您详细介绍。";
    }

    /**
     * 尝试从消息中提取产品并查询
     */
    private ProductInfo tryFindProduct(String message) {
        // 尝试按关键词匹配
        String[] productKeywords = {"手表", "耳机", "充电宝", "智能手表", "降噪耳机"};
        for (String keyword : productKeywords) {
            if (message.contains(keyword)) {
                ProductInfo info = productQueryTool.queryProduct(keyword);
                if (info != null) {
                    return info;
                }
            }
        }
        // 尝试按产品ID查询
        if (message.matches(".*P\\d{3}.*")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("P\\d{3}");
            java.util.regex.Matcher m = p.matcher(message);
            if (m.find()) {
                return productQueryTool.queryProduct(m.group());
            }
        }
        return null;
    }

    /**
     * 格式化产品推荐回复
     */
    private String formatProductRecommendation(ProductInfo product, String userNeed) {
        return String.format("""
                根据您的需求，为您推荐：**%s**
                
                %s
                
                💡 **推荐理由**：这款产品%s，非常适合您的需求。
                如需对比其他产品，或了解更多细节，请随时告诉我！
                """,
                product.getName(),
                product.toDisplayText(),
                product.getFeatures() != null && !product.getFeatures().isEmpty()
                        ? "具有" + String.join("、", product.getFeatures()) + "等特点"
                        : "品质出色");
    }
}
