package com.cs.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单信息模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderInfo {

    private String orderId;
    private String userId;
    private String productName;
    private Double amount;
    private String status;       // PENDING / SHIPPED / DELIVERED / CANCELLED
    private String address;
    private String logisticsInfo;
    private String createdAt;
    private String updatedAt;

    /**
     * 格式化为可读文本
     */
    public String toDisplayText() {
        return String.format("""
                订单号: %s
                商品: %s
                金额: ¥%.2f
                状态: %s
                收货地址: %s
                物流信息: %s
                下单时间: %s
                """,
                orderId, productName, amount, status, address,
                logisticsInfo != null ? logisticsInfo : "暂无",
                createdAt);
    }
}
