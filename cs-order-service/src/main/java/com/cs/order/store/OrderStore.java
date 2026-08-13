package com.cs.order.store;

import com.cs.common.model.OrderInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内订单库（MVP Mock）。生产可替换为真实订单中心 / DB。
 */
@Slf4j
@Component
public class OrderStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, OrderInfo> orders = new ConcurrentHashMap<>();

    public OrderStore() {
        seed();
    }

    public OrderInfo findById(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        OrderInfo order = orders.get(orderId.trim().toUpperCase());
        if (order != null) {
            log.info("Order found: {}", orderId);
        } else {
            log.warn("Order not found: {}", orderId);
        }
        return order;
    }

    public String modifyAddress(String orderId, String newAddress) {
        OrderInfo order = findById(orderId);
        if (order == null) {
            return "订单不存在";
        }
        if (!"PENDING".equals(order.getStatus()) && !"PAID".equals(order.getStatus())) {
            return "订单已发货，无法修改地址，请转售后处理";
        }
        order.setAddress(newAddress);
        order.setUpdatedAt(LocalDateTime.now().format(FMT));
        log.info("Address modified: orderId={}, newAddress={}", orderId, newAddress);
        return "地址修改成功，新地址：" + newAddress;
    }

    private void seed() {
        put(OrderInfo.builder()
                .orderId("ORD20260609001").userId("U001")
                .productName("智能手表 Pro").amount(1299.00)
                .status("DELIVERED").address("北京市海淀区中关村大街1号")
                .logisticsInfo("已签收，签收人：本人")
                .createdAt("2026-06-05 14:30:00").updatedAt("2026-06-09 10:15:00")
                .build());
        put(OrderInfo.builder()
                .orderId("ORD20260608002").userId("U001")
                .productName("无线降噪耳机").amount(899.00)
                .status("SHIPPED").address("北京市海淀区中关村大街1号")
                .logisticsInfo("运输中，已到达北京转运中心")
                .createdAt("2026-06-08 09:20:00").updatedAt("2026-06-09 08:00:00")
                .build());
        put(OrderInfo.builder()
                .orderId("ORD20260607003").userId("U002")
                .productName("便携充电宝 20000mAh").amount(299.00)
                .status("PENDING").address("上海市浦东新区陆家嘴环路1000号")
                .logisticsInfo("待发货")
                .createdAt("2026-06-07 16:45:00").updatedAt("2026-06-07 16:45:00")
                .build());
        put(OrderInfo.builder()
                .orderId("ORD20260601001").userId("U100001")
                .productName("智能蓝牙耳机 Pro").amount(299.00)
                .status("DELIVERED").address("上海市浦东新区张江高科技园区")
                .logisticsInfo("已签收")
                .createdAt("2026-06-01 10:00:00").updatedAt("2026-06-03 18:00:00")
                .build());
        put(OrderInfo.builder()
                .orderId("ORD20260602001").userId("U100001")
                .productName("轻薄笔记本电脑 AirBook 14").amount(4599.00)
                .status("SHIPPED").address("上海市浦东新区张江高科技园区")
                .logisticsInfo("运输中")
                .createdAt("2026-06-02 11:00:00").updatedAt("2026-06-04 09:00:00")
                .build());
        put(OrderInfo.builder()
                .orderId("ORD20260603001").userId("U100002")
                .productName("智能手表 Watch S8").amount(3198.00)
                .status("PAID").address("北京市海淀区中关村")
                .logisticsInfo("待发货")
                .createdAt("2026-06-03 12:00:00").updatedAt("2026-06-03 12:30:00")
                .build());
        log.info("OrderStore seeded with {} orders", orders.size());
    }

    private void put(OrderInfo order) {
        orders.put(order.getOrderId(), order);
    }
}
