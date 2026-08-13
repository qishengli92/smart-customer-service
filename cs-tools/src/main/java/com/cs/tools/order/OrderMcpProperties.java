package com.cs.tools.order;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订单 MCP 客户端配置（{@code cs.order.mcp}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.order.mcp")
public class OrderMcpProperties {

    /**
     * 是否启用 MCP 订单客户端。
     */
    private boolean enabled = true;

    /**
     * 订单服务 MCP Streamable HTTP 端点，例如 {@code http://localhost:8081/mcp}。
     */
    private String url = "http://localhost:8081/mcp";

    /**
     * 请求超时（秒）。
     */
    private int timeoutSeconds = 30;
}
