package com.cs.common.exception;

/**
 * 路由异常 - 意图识别失败
 */
public class RoutingException extends CustomerServiceException {

    public RoutingException(String message) {
        super("ROUTING_ERROR", message);
    }

    public RoutingException(String message, Throwable cause) {
        super("ROUTING_ERROR", message, cause);
    }
}
