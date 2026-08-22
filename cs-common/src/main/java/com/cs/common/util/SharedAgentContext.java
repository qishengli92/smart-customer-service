package com.cs.common.util;

/**
 * 跨 Agent 共享的会话上下文文本：编排器写入，领域 Agent 读取。
 * <p>
 * 各 ReActAgent 的 {@code AgentStateStore} 按 agent 隔离，不能互相看到对话；
 * 订单号、子意图、近期对话必须经此通道合并传递。
 */
public final class SharedAgentContext {

    public static final String ORDER_ID_PREFIX = "已知订单号：";
    public static final String SUB_INTENT_PREFIX = "已知子意图：";

    private SharedAgentContext() {}

    public static String render(String orderId, String subIntent,
                                String fromAgent, String toAgent, String recentDialogue) {
        StringBuilder sb = new StringBuilder();
        sb.append("【会话共享上下文】下列信息已在本会话确认，禁止再向用户索取已给出的订单号或意图。\n");
        if (notBlank(orderId)) {
            sb.append(ORDER_ID_PREFIX).append(orderId).append('\n');
        }
        if (notBlank(subIntent)) {
            sb.append(SUB_INTENT_PREFIX).append(subIntent).append('\n');
        }
        if (notBlank(fromAgent) && notBlank(toAgent) && !fromAgent.equals(toAgent)
                && !"router".equals(fromAgent) && !"system".equals(fromAgent)) {
            sb.append("Agent 切换：").append(fromAgent).append(" → ").append(toAgent)
                    .append("，请承接上一 Agent 已掌握的信息，不要从头询问。\n");
        }
        if (notBlank(recentDialogue)) {
            sb.append("近期对话：\n").append(recentDialogue);
        }
        return sb.toString();
    }

    public static boolean hasUsableContent(String orderId, String subIntent,
                                           String fromAgent, String toAgent, String recentDialogue) {
        if (notBlank(orderId) || notBlank(subIntent)) {
            return true;
        }
        if (notBlank(fromAgent) && notBlank(toAgent) && !fromAgent.equals(toAgent)
                && !"router".equals(fromAgent) && !"system".equals(fromAgent)) {
            return true;
        }
        return notBlank(recentDialogue) && recentDialogue.lines().count() > 1;
    }

    public static String orderIdOf(String context) {
        String tagged = valueAfter(context, ORDER_ID_PREFIX);
        if (tagged != null) {
            String id = OrderIdExtractor.extract(tagged);
            if (id != null) {
                return id;
            }
        }
        return OrderIdExtractor.extract(context);
    }

    public static String subIntentOf(String context) {
        return valueAfter(context, SUB_INTENT_PREFIX);
    }

    private static String valueAfter(String context, String prefix) {
        if (context == null || prefix == null) {
            return null;
        }
        int i = context.indexOf(prefix);
        if (i < 0) {
            return null;
        }
        int start = i + prefix.length();
        int end = context.indexOf('\n', start);
        String value = (end < 0 ? context.substring(start) : context.substring(start, end)).trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
