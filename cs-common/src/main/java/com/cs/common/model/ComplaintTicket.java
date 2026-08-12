package com.cs.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 投诉工单模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplaintTicket {

    private String ticketId;
    private String content;
    private String severity;     // LOW / MEDIUM / HIGH / CRITICAL
    private String orderId;
    private String status;       // OPEN / PROCESSING / ESCALATED / RESOLVED / CLOSED
    private String assignedTo;
    private String createdAt;

    /**
     * 格式化为可读文本
     */
    public String toDisplayText() {
        return String.format("""
                投诉工单号: %s
                投诉内容: %s
                严重程度: %s
                关联订单: %s
                状态: %s
                处理人: %s
                创建时间: %s
                """,
                ticketId, content, severity,
                orderId != null ? orderId : "无",
                status,
                assignedTo != null ? assignedTo : "待分配",
                createdAt);
    }
}
