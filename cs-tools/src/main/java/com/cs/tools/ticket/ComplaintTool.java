package com.cs.tools.ticket;

import com.cs.common.model.ComplaintTicket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 投诉工单工具
 */
@Slf4j
@Component
public class ComplaintTool {

    private final Map<String, ComplaintTicket> ticketDB = new ConcurrentHashMap<>();
    private final AtomicLong ticketCounter = new AtomicLong(5000);

    /**
     * 创建投诉工单
     */
    public ComplaintTicket createComplaint(String content, String severity, String orderId) {
        String ticketId = "CP" + ticketCounter.incrementAndGet();
        ComplaintTicket ticket = ComplaintTicket.builder()
                .ticketId(ticketId)
                .content(content)
                .severity(severity)
                .orderId(orderId)
                .status("OPEN")
                .createdAt(java.time.Instant.now().toString())
                .build();
        ticketDB.put(ticketId, ticket);
        log.info("Complaint ticket created: ticketId={}, severity={}", ticketId, severity);
        return ticket;
    }

    /**
     * 升级投诉
     */
    public String escalateComplaint(String ticketId, String reason, String targetLevel) {
        ComplaintTicket ticket = ticketDB.get(ticketId);
        if (ticket == null) {
            return "工单不存在";
        }
        ticket.setStatus("ESCALATED");
        ticket.setAssignedTo(targetLevel + "_TEAM");
        log.info("Complaint escalated: ticketId={}, target={}", ticketId, targetLevel);
        return String.format("投诉工单 %s 已升级至%s，原因：%s", ticketId, targetLevel, reason);
    }
}
