package com.cs.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 产品信息模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductInfo {

    private String productId;
    private String name;
    private String category;
    private String specification;
    private Double price;
    private Integer stock;
    private String description;
    private List<String> features;

    /**
     * 格式化为可读文本
     */
    public String toDisplayText() {
        return String.format("""
                产品名称: %s
                产品ID: %s
                分类: %s
                规格: %s
                价格: ¥%.2f
                库存: %d
                描述: %s
                特点: %s
                """,
                name, productId, category, specification, price, stock,
                description, features != null ? String.join("、", features) : "无");
    }
}
