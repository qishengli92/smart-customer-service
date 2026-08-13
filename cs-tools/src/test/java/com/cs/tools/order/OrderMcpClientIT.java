package com.cs.tools.order;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要本地 {@code cs-order-service} 已启动（默认 :8081）。
 */
class OrderMcpClientIT {

    @Test
    void queryOrderViaShortLivedClient() {
        String base = resolveOrderBaseUrl();
        Assumptions.assumeTrue(base != null, "cs-order-service not running on :8081/:8082");

        OrderMcpProperties properties = new OrderMcpProperties();
        properties.setUrl(base + "/mcp");
        properties.setTimeoutSeconds(30);
        OrderMcpClient client = new OrderMcpClient(properties);

        McpSchema.CallToolResult result = client.callTool(
                "query_order", Map.of("orderId", "ORD20260609001"));
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertNotNull(result.structuredContent());
        assertTrue(result.structuredContent().toString().contains("ORD20260609001"));

        McpSchema.CallToolResult again = client.callTool(
                "query_order", Map.of("orderId", "ORD20260608002"));
        assertNotNull(again);
        assertFalse(Boolean.TRUE.equals(again.isError()));
    }

    private static String resolveOrderBaseUrl() {
        for (String base : new String[]{"http://127.0.0.1:8081", "http://127.0.0.1:8082"}) {
            if (healthy(base + "/actuator/health")) {
                return base;
            }
        }
        return null;
    }

    private static boolean healthy(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
