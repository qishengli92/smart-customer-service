package com.cs.order.store;

import com.cs.common.model.OrderInfo;
import com.cs.order.entity.CsOrderEntity;
import com.cs.order.repo.CsOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 订单仓储：读写 PostgreSQL {@code cs_order}，对外仍暴露 {@link OrderInfo}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final CsOrderRepository orderRepository;

    @Transactional(readOnly = true)
    public OrderInfo findById(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        String key = orderId.trim();
        Optional<CsOrderEntity> entity = orderRepository.findByOrderIdOrOrderNo(key);
        if (entity.isEmpty()) {
            log.warn("Order not found: {}", orderId);
            return null;
        }
        log.info("Order found: {}", orderId);
        return toOrderInfo(entity.get());
    }

    @Transactional
    public String modifyAddress(String orderId, String newAddress) {
        if (orderId == null || orderId.isBlank()) {
            return "订单不存在";
        }
        Optional<CsOrderEntity> opt = orderRepository.findByOrderIdOrOrderNo(orderId.trim());
        if (opt.isEmpty()) {
            return "订单不存在";
        }
        CsOrderEntity entity = opt.get();
        String status = normalizeStatus(entity.getStatus());
        if (!"PENDING".equals(status) && !"PAID".equals(status)) {
            return "订单已发货，无法修改地址，请转售后处理";
        }
        Map<String, Object> addr = entity.getShippingAddr() != null
                ? new LinkedHashMap<>(entity.getShippingAddr())
                : new LinkedHashMap<>();
        addr.put("detail", newAddress);
        // 兼容扁平地址展示：同时写入完整地址字段
        addr.put("full", newAddress);
        entity.setShippingAddr(addr);
        entity.setUpdatedAt(Instant.now());
        orderRepository.save(entity);
        log.info("Address modified: orderId={}, newAddress={}", orderId, newAddress);
        return "地址修改成功，新地址：" + newAddress;
    }

    private OrderInfo toOrderInfo(CsOrderEntity entity) {
        String displayId = entity.getOrderNo() != null ? entity.getOrderNo() : entity.getOrderId();
        BigDecimal amount = entity.getTotalAmount() != null
                ? entity.getTotalAmount()
                : entity.getUnitPrice();
        return OrderInfo.builder()
                .orderId(displayId)
                .userId(entity.getUserId())
                .productName(entity.getProductName())
                .amount(amount != null ? amount.doubleValue() : 0.0)
                .status(normalizeStatus(entity.getStatus()))
                .address(formatAddress(entity.getShippingAddr()))
                .logisticsInfo(resolveLogistics(entity))
                .createdAt(formatInstant(entity.getCreatedAt()))
                .updatedAt(formatInstant(entity.getUpdatedAt()))
                .build();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "PENDING";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static String formatAddress(Map<String, Object> addr) {
        if (addr == null || addr.isEmpty()) {
            return "";
        }
        if (addr.get("full") != null) {
            return String.valueOf(addr.get("full"));
        }
        StringBuilder sb = new StringBuilder();
        for (String key : new String[]{"province", "city", "district", "detail"}) {
            Object v = addr.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                sb.append(v);
            }
        }
        if (sb.length() == 0 && addr.get("detail") != null) {
            return String.valueOf(addr.get("detail"));
        }
        return sb.toString();
    }

    private static String resolveLogistics(CsOrderEntity entity) {
        String status = normalizeStatus(entity.getStatus());
        if (entity.getTrackingNo() != null && !entity.getTrackingNo().isBlank()) {
            return "运单号：" + entity.getTrackingNo();
        }
        return switch (status) {
            case "DELIVERED" -> "已签收";
            case "SHIPPED" -> "运输中";
            case "PAID" -> "已付款，待发货";
            case "PENDING" -> "待发货";
            case "CANCELLED" -> "已取消";
            default -> "暂无物流信息";
        };
    }

    private static String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return FMT.format(instant);
    }
}
