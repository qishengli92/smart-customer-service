package com.cs.common.exception;

/**
 * 风控异常 - 操作被拒绝
 */
public class RiskControlException extends CustomerServiceException {

    public RiskControlException(String message) {
        super("RISK_CONTROL_ERROR", message);
    }
}
