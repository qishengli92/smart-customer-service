package com.cs.common.exception;

/**
 * 智能客服系统基础异常
 */
public class CustomerServiceException extends RuntimeException {

    private final String errorCode;

    public CustomerServiceException(String message) {
        super(message);
        this.errorCode = "CS_ERROR";
    }

    public CustomerServiceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CustomerServiceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
