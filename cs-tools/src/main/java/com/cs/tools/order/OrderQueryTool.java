package com.cs.tools.order;

import com.cs.common.model.OrderInfo;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单查询工具（AgentScope {@link Tool} 注册，供 ReActAgent Toolkit 调用）
 */
@Slf4j
@Component
public class OrderQueryTool {

    private static final Map<String, OrderInfo> ORDER_DB = new HashMap<>();

    static {
        ORDER_DB.put("ORD20260609001", OrderInfo.builder()
                .orderId("ORD20260609001").userId("U001")
                .productName("智能手表 Pro").amount(1299.00)
                .status("DELIVERED").address("北京市海淀区中关村大街1号")
                .logisticsInfo("已签收，签收人：本人")
                .createdAt("2026-06-05 14:30:00").updatedAt("2026-06-09 10:15:00")
                .build());
        ORDER_DB.put("ORD20260608002", OrderInfo.builder()
                .orderId("ORD20260608002").userId("U001")
                .productName("无线降噪耳机").amount(899.00)
                .status("SHIPPED").address("北京市海淀区中关村大街1号")
                .logisticsInfo("运输中，已到达北京转运中心")
                .createdAt("2026-06-08 09:20:00").updatedAt("2026-06-09 08:00:00")
                .build());
        ORDER_DB.put("ORD20260607003", OrderInfo.builder()
                .orderId("ORD20260607003").userId("U002")
                .productName("便携充电宝 20000mAh").amount(299.00)
                .status("PENDING").address("上海市浦东新区陆家嘴环路1000号")
                .logisticsInfo("待发货")
                .createdAt("2026-06-07 16:45:00").updatedAt("2026-06-07 16:45:00")
                .build());
        ORDER_DB.put("ORD20260601001", OrderInfo.builder()
                .orderId("ORD20260601001").userId("U100001")
                .productName("智能蓝牙耳机 Pro").amount(299.00)
                .status("DELIVERED").address("上海市浦东新区张江高科技园区")
                .logisticsInfo("已签收")
                .createdAt("2026-06-01 10:00:00").updatedAt("2026-06-03 18:00:00")
                .build());
        ORDER_DB.put("ORD20260602001", OrderInfo.builder()
                .orderId("ORD20260602001").userId("U100001")
                .productName("轻薄笔记本电脑 AirBook 14").amount(4599.00)
                .status("SHIPPED").address("上海市浦东新区张江高科技园区")
                .logisticsInfo("运输中")
                .createdAt("2026-06-02 11:00:00").updatedAt("2026-06-04 09:00:00")
                .build());
        ORDER_DB.put("ORD20260603001", OrderInfo.builder()
                .orderId("ORD20260603001").userId("U100002")
                .productName("智能手表 Watch S8").amount(3198.00)
                .status("PAID").address("北京市海淀区中关村")
                .logisticsInfo("待发货")
                .createdAt("2026-06-03 12:00:00").updatedAt("2026-06-03 12:30:00")
                .build());
    }

    public OrderInfo queryOrder(String orderId) {
        if (orderId == null) {
            return null;
        }
        OrderInfo order = ORDER_DB.get(orderId.toUpperCase());
        if (order != null) {
            log.info("Order found: {}", orderId);
            return order;
        }
        log.warn("Order not found: {}", orderId);
        return null;
    }

    @Tool(name = "query_order", description = "根据订单号查询订单详情、状态与物流信息。订单号通常以 ORD 开头。")
    public String queryOrderTool(
            @ToolParam(name = "orderId", description = "订单号，例如 ORD20260609001") String orderId) {
        OrderInfo order = queryOrder(orderId);
        if (order == null) {
            return "未找到订单：" + orderId;
        }
        return order.toDisplayText()
                + (order.getLogisticsInfo() != null ? "\n物流：" + order.getLogisticsInfo() : "");
    }

    @Tool(name = "modify_order_address", description = "修改未发货订单的收货地址。已发货订单不可修改。")
    public String modifyAddressTool(
            @ToolParam(name = "orderId", description = "订单号") String orderId,
            @ToolParam(name = "newAddress", description = "新的收货地址") String newAddress) {
        return modifyAddress(orderId, newAddress);
    }

    public String modifyAddress(String orderId, String newAddress) {
        OrderInfo order = queryOrder(orderId);
        if (order == null) {
            return "订单不存在";
        }
        if (!"PENDING".equals(order.getStatus()) && !"PAID".equals(order.getStatus())) {
            return "订单已发货，无法修改地址，请转售后处理";
        }
        order.setAddress(newAddress);
        log.info("Address modified: orderId={}, newAddress={}", orderId, newAddress);
        return "地址修改成功，新地址：" + newAddress;
    }
}
