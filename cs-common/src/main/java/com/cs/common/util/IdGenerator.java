package com.cs.common.util;

import java.util.UUID;

/**
 * ID 生成器
 */
public final class IdGenerator {

    private IdGenerator() {}

    /**
     * 生成会话ID
     */
    public static String sessionId() {
        return "sess_" + shortId();
    }

    /**
     * 生成消息ID
     */
    public static String messageId() {
        return "msg_" + shortId();
    }

    /**
     * 生成订单ID
     */
    public static String orderId() {
        return "ORD" + System.currentTimeMillis();
    }

    /**
     * 生成售后单ID
     */
    public static String ticketId() {
        return "TK" + System.currentTimeMillis();
    }

    /**
     * 生成投诉工单ID
     */
    public static String complaintId() {
        return "CP" + System.currentTimeMillis();
    }

    /**
     * 生成 Trace ID
     */
    public static String traceId() {
        return "trace_" + shortId();
    }

    /**
     * 生成确认单 ID
     */
    public static String confirmationId() {
        return "cfm_" + shortId();
    }

    /**
     * 生成交接单 ID
     */
    public static String handoffId() {
        return "ho_" + shortId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
