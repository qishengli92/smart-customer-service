package com.cs.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订单号实体提取。订单号只是槽位，不单独构成「订单查询」意图。
 */
public final class OrderIdExtractor {

    private static final Pattern ORDER_ID = Pattern.compile("ORD\\d+", Pattern.CASE_INSENSITIVE);

    /**
     * 出现这些词时，用户是在发起新业务，而不是单纯把订单号填回当前 Agent。
     */
    private static final Pattern NOT_SLOT_FILL = Pattern.compile(
            "物流|快递|到哪了|发货|改地址|修改地址|查订单|查询订单|"
                    + "退款|退货|换货|退换|维修|售后|退钱|"
                    + "转人工|人工客服|真人|"
                    + "投诉|保修|发票|开票|"
                    + "换个问题|换个话题");

    private OrderIdExtractor() {}

    public static String extract(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = ORDER_ID.matcher(text);
        return matcher.find() ? matcher.group().toUpperCase() : null;
    }

    /**
     * 用户主要是在提供/确认订单号（填槽），而非切换到查物流、退换货等新意图。
     */
    public static boolean isProvidingOrderId(String message) {
        if (extract(message) == null) {
            return false;
        }
        return message == null || !NOT_SLOT_FILL.matcher(message).find();
    }
}
