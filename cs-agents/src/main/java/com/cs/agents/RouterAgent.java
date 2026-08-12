package com.cs.agents;

import com.cs.common.enums.IntentType;
import com.cs.common.model.RoutingDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图路由（MVP：规则引擎优先，异常回退 {@link IntentType#CHITCHAT}）。
 * <p>
 * 刻意不走 LLM，保证路由稳定可测；领域回复才用 AgentScope {@code ReActAgent}。
 * MVP 裁剪：{@code PRE_SALES→KNOWLEDGE}，{@code COMPLAINT→HUMAN_SERVICE}。
 * 输出 {@link RoutingDecision}，由编排器 sticky / 派发消费。
 */
@Slf4j
@Component
public class RouterAgent {

    public RoutingDecision route(String userMessage) {
        log.info("RouterAgent processing: {}",
                userMessage.substring(0, Math.min(50, userMessage.length())));
        try {
            RoutingDecision decision = ruleBasedRoute(userMessage);
            decision = applyMvpIntentMapping(decision);
            log.info("Routing decision: intent={}, confidence={}, reason={}",
                    decision.getIntent(), decision.getConfidence(), decision.getReason());
            return decision;
        } catch (Exception e) {
            log.error("Routing failed, fallback to CHITCHAT: {}", e.getMessage());
            return RoutingDecision.builder()
                    .intent(IntentType.CHITCHAT)
                    .confidence(0.3)
                    .reason("路由异常，回退到闲聊")
                    .build();
        }
    }

    /**
     * 轻量意图预判（供 sticky 打断）：与 {@link #route} 同一套规则，不打完整路由日志。
     */
    public IntentType hintIntent(String userMessage) {
        try {
            return applyMvpIntentMapping(ruleBasedRoute(userMessage)).getIntent();
        } catch (Exception e) {
            return IntentType.CHITCHAT;
        }
    }

    private RoutingDecision applyMvpIntentMapping(RoutingDecision decision) {
        if (decision.getIntent() == IntentType.PRE_SALES) {
            return RoutingDecision.builder()
                    .intent(IntentType.KNOWLEDGE)
                    .subIntent(decision.getSubIntent() != null ? decision.getSubIntent() : "FAQ")
                    .confidence(decision.getConfidence())
                    .entities(decision.getEntities())
                    .reason("MVP: PRE_SALES 映射为 KNOWLEDGE — " + decision.getReason())
                    .build();
        }
        if (decision.getIntent() == IntentType.COMPLAINT) {
            return RoutingDecision.builder()
                    .intent(IntentType.HUMAN_SERVICE)
                    .subIntent("COMPLAINT_ESCALATE")
                    .confidence(decision.getConfidence())
                    .entities(decision.getEntities())
                    .reason("MVP: COMPLAINT 映射为 HUMAN_SERVICE — " + decision.getReason())
                    .build();
        }
        return decision;
    }

    private RoutingDecision ruleBasedRoute(String message) {
        String lower = message.toLowerCase();
        Map<String, String> entities = new HashMap<>();

        // 人工转接（优先）
        if (containsAny(lower, "转人工", "人工客服", "真人客服", "找人工")) {
            return RoutingDecision.builder()
                    .intent(IntentType.HUMAN_SERVICE)
                    .subIntent("TRANSFER")
                    .confidence(0.95)
                    .entities(entities)
                    .reason("用户要求人工服务")
                    .build();
        }

        // 售后（退款等）优先于通用「订单」
        if (containsAny(lower, "退款", "退货", "换货", "维修", "售后", "退钱")) {
            if (message.toUpperCase().matches("(?s).*ORD\\d+.*")) {
                entities.put("orderId", extractOrderId(message));
            }
            String subIntent = lower.contains("退款") || lower.contains("退钱") ? "REFUND" :
                    lower.contains("退货") ? "RETURN" : "EXCHANGE";
            return RoutingDecision.builder()
                    .intent(IntentType.AFTER_SALES)
                    .subIntent(subIntent)
                    .confidence(0.9)
                    .entities(entities)
                    .reason("包含售后支持关键词")
                    .build();
        }

        // 投诉 → 后续映射 HUMAN
        if (containsAny(lower, "投诉", "不满", "差评", "赔偿")) {
            return RoutingDecision.builder()
                    .intent(IntentType.COMPLAINT)
                    .subIntent("COMPLAINT")
                    .confidence(0.85)
                    .entities(entities)
                    .reason("包含投诉关键词")
                    .build();
        }

        // 订单查询
        if (containsAny(lower, "订单", "物流", "快递", "到哪了", "发货", "地址", "ord")) {
            if (message.toUpperCase().matches("(?s).*ORD\\d+.*")) {
                entities.put("orderId", extractOrderId(message));
            }
            return RoutingDecision.builder()
                    .intent(IntentType.ORDER)
                    .subIntent("QUERY")
                    .confidence(0.9)
                    .entities(entities)
                    .reason("包含订单服务关键词")
                    .build();
        }

        // 知识问答（含发票/保修等 MVP Demo 关键词）
        if (containsAny(lower, "怎么用", "使用方法", "保修", "质保", "政策", "如何", "faq",
                "发票", "开票", "开具", "会员", "vip", "配送时效", "退货政策", "说明书")) {
            return RoutingDecision.builder()
                    .intent(IntentType.KNOWLEDGE)
                    .subIntent("FAQ")
                    .confidence(0.85)
                    .entities(entities)
                    .reason("包含知识问答关键词")
                    .build();
        }

        // 售前（MVP 将映射到知识）
        if (containsAny(lower, "推荐", "价格", "多少钱", "对比", "买哪个", "产品", "规格")) {
            return RoutingDecision.builder()
                    .intent(IntentType.PRE_SALES)
                    .subIntent("RECOMMEND")
                    .confidence(0.8)
                    .entities(entities)
                    .reason("包含售前咨询关键词")
                    .build();
        }

        // 泛化「客服」偏闲聊引导，避免误伤
        if (containsAny(lower, "人工") && !containsAny(lower, "取消")) {
            return RoutingDecision.builder()
                    .intent(IntentType.HUMAN_SERVICE)
                    .subIntent("TRANSFER")
                    .confidence(0.9)
                    .entities(entities)
                    .reason("用户提到人工")
                    .build();
        }

        return RoutingDecision.builder()
                .intent(IntentType.CHITCHAT)
                .subIntent("GREETING")
                .confidence(0.6)
                .entities(entities)
                .reason("未匹配到特定意图")
                .build();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String extractOrderId(String text) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("ORD\\d+",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().toUpperCase() : "";
    }
}
