package com.cs.tools.refund;

import com.cs.common.model.AfterSalesTicket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 退款写工具（敏感）：仅允许经 {@link PermissionGate} / {@link ConfirmedToolExecutor} 调用。
 * <p>
 * 勿注册进 ReAct Toolkit 让模型直接扣款；MVP 为内存工单。
 */
@Slf4j
@Component
public class RefundTool {

    private final Map<String, AfterSalesTicket> ticketDB = new ConcurrentHashMap<>();
    private final AtomicLong ticketCounter = new AtomicLong(1000);

    /**
     * 申请退款
     */
    public AfterSalesTicket applyRefund(String orderId, String reason, Double amount) {
        String ticketId = "TK" + ticketCounter.incrementAndGet();
        AfterSalesTicket ticket = AfterSalesTicket.builder()
                .ticketId(ticketId)
                .orderId(orderId)
                .type("REFUND")
                .reason(reason)
                .refundAmount(amount)
                .status("PENDING")
                .createdAt(java.time.Instant.now().toString())
                .build();
        ticketDB.put(ticketId, ticket);
        log.info("Refund ticket created: ticketId={}, orderId={}, amount={}",
                ticketId, orderId, amount);
        return ticket;
    }

    /**
     * 申请退货
     */
    public AfterSalesTicket applyReturn(String orderId, String reason) {
        String ticketId = "TK" + ticketCounter.incrementAndGet();
        AfterSalesTicket ticket = AfterSalesTicket.builder()
                .ticketId(ticketId)
                .orderId(orderId)
                .type("RETURN")
                .reason(reason)
                .status("PENDING")
                .createdAt(java.time.Instant.now().toString())
                .build();
        ticketDB.put(ticketId, ticket);
        log.info("Return ticket created: ticketId={}, orderId={}", ticketId, orderId);
        return ticket;
    }

    /**
     * 查询售后进度
     */
    public AfterSalesTicket checkProgress(String ticketId) {
        return ticketDB.get(ticketId);
    }
}
