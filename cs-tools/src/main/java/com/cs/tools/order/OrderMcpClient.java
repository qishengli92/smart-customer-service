package com.cs.tools.order;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 订单 MCP 客户端：每次工具调用使用短生命周期连接，避免长会话断开后
 * 出现 {@code MCP session with server terminated}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cs.order.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderMcpClient {

    public static final String CLIENT_NAME = "order-service";

    private final OrderMcpProperties properties;

    public McpSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) {
        McpClientWrapper client = null;
        try {
            client = openClient();
            McpSchema.CallToolResult result = client
                    .callTool(toolName, arguments)
                    .block(Duration.ofSeconds(properties.getTimeoutSeconds()));
            if (result == null) {
                throw new IllegalStateException("Order MCP returned null for tool " + toolName);
            }
            return result;
        } catch (Exception e) {
            log.error("Order MCP call '{}' failed: {}", toolName, e.getMessage());
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        } finally {
            closeQuietly(client);
        }
    }

    private McpClientWrapper openClient() {
        log.debug("Opening order MCP client -> {}", properties.getUrl());
        McpClientWrapper client = McpClientBuilder.create(CLIENT_NAME)
                .streamableHttpTransport(properties.getUrl())
                .protocolVersions("2024-11-05", "2025-03-26", "2025-06-18")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .initializationTimeout(Duration.ofSeconds(30))
                .buildAsync()
                .block();
        if (client == null) {
            throw new IllegalStateException("Failed to create order MCP client for " + properties.getUrl());
        }
        client.initialize().block(Duration.ofSeconds(30));
        return client;
    }

    private static void closeQuietly(McpClientWrapper client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
