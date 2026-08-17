package com.cs.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 订单服务启动类：通过 MCP Streamable HTTP 对外暴露订单查询/改址工具。
 * <p>
 * 订单数据持久化到 PostgreSQL {@code cs_order}（与 gateway 共用库）。
 */
@SpringBootApplication
@EntityScan(basePackages = "com.cs.order.entity")
@EnableJpaRepositories(basePackages = "com.cs.order.repo")
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
