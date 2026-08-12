package com.cs.infra.observability;

/**
 * 当前请求的追踪上下文（boundedElastic 线程内传递 sessionId / userId）
 */
public final class TraceContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> PARENT_SPAN_ID = new ThreadLocal<>();

    private TraceContext() {}

    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void setParentSpanId(String spanId) {
        PARENT_SPAN_ID.set(spanId);
    }

    public static String getParentSpanId() {
        return PARENT_SPAN_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
        USER_ID.remove();
        PARENT_SPAN_ID.remove();
    }
}
