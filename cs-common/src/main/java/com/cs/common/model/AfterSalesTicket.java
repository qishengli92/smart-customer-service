package com.cs.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 售后单模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesTicket {

    private String ticketId;
    private String orderId;
    private String type;         // REFUND / RETURN / EXCHANGE / REPAIR
    private String reason;
    private Double refundAmount;
    private String status;       // PENDING / APPROVED / PROCESSING / COMPLETED / REJECTED
    private String createdAt;

    /**
     * 格式化为可读文本
     */
    public String toDisplayText() {
        return String.format("""
                售后单号: %s
                订单号: %s
                类型: %s
                原因: %s
                退款金额: ¥%.2f
                状态: %s
                创建时间: %s
                """,
                ticketId, orderId, type, reason,
                refundAmount != null ? refundAmount : 0.0,
                status, createdAt);
    }
}
