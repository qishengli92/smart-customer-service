package com.cs.tools.product;

import com.cs.common.model.ProductInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 产品查询工具
 * <p>
 * 在 AgentScope Java 中通过 @Tool 注解注册为 LLM 可调用的工具。
 * 当前版本使用 Mock 数据，后续接入真实产品中心 API。
 */
@Slf4j
@Component
public class ProductQueryTool {

    /**
     * Mock 产品数据
     */
    private static final Map<String, ProductInfo> PRODUCT_DB = new HashMap<>();

    static {
        PRODUCT_DB.put("P001", ProductInfo.builder()
                .productId("P001").name("智能手表 Pro").category("智能穿戴")
                .specification("1.4英寸 AMOLED / 血氧监测 / GPS / 5ATM防水")
                .price(1299.00).stock(156)
                .description("旗舰智能手表，支持血氧、心率、睡眠全方位健康监测")
                .features(List.of("血氧监测", "GPS定位", "5ATM防水", "14天续航"))
                .build());
        PRODUCT_DB.put("P002", ProductInfo.builder()
                .productId("P002").name("无线降噪耳机").category("音频设备")
                .specification("40mm动圈 / ANC主动降噪 / 蓝牙5.3 / 30小时续航")
                .price(899.00).stock(230)
                .description("高品质无线降噪耳机，沉浸式音频体验")
                .features(List.of("ANC降噪", "蓝牙5.3", "30小时续航", "折叠设计"))
                .build());
        PRODUCT_DB.put("P003", ProductInfo.builder()
                .productId("P003").name("便携充电宝 20000mAh").category("充电配件")
                .specification("20000mAh / 65W快充 / USB-C / 重量380g")
                .price(299.00).stock(500)
                .description("大容量快充移动电源，支持笔记本电脑充电")
                .features(List.of("65W快充", "多口输出", "航空级电芯", "LED电量显示"))
                .build());
    }

    /**
     * 查询产品信息
     */
    public ProductInfo queryProduct(String productKey) {
        // 先按ID查
        ProductInfo info = PRODUCT_DB.get(productKey);
        if (info != null) {
            log.info("Product found by ID: {}", productKey);
            return info;
        }
        // 再按名称模糊匹配
        for (ProductInfo p : PRODUCT_DB.values()) {
            if (p.getName().contains(productKey)) {
                log.info("Product found by name: {}", productKey);
                return p;
            }
        }
        log.warn("Product not found: {}", productKey);
        return null;
    }

    /**
     * 对比产品
     */
    public String compareProducts(List<String> productKeys) {
        List<ProductInfo> products = new ArrayList<>();
        for (String key : productKeys) {
            ProductInfo info = queryProduct(key);
            if (info != null) {
                products.add(info);
            }
        }
        if (products.size() < 2) {
            return "需要至少两个有效产品才能进行对比";
        }
        StringBuilder sb = new StringBuilder("产品对比：\n\n");
        for (ProductInfo p : products) {
            sb.append(p.toDisplayText()).append("---\n");
        }
        return sb.toString();
    }
}
