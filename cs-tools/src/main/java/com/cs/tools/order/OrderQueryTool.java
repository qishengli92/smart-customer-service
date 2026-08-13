package com.cs.tools.order;

import com.cs.common.model.OrderInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订单查询/改址工具：对外仍是 AgentScope {@code @Tool}，内部通过 MCP 调用 {@code cs-order-service}。
 * <p>
 * 不直接把长生命周期 MCP client 挂到 Toolkit，避免会话断开后工具永久失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueryTool {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}$");

    private final OrderMcpClient orderMcpClient;
    private final ObjectMapper objectMapper;

    public OrderInfo queryOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        try {
            McpSchema.CallToolResult result = orderMcpClient.callTool(
                    "query_order", Map.of("orderId", orderId));
            if (Boolean.TRUE.equals(result.isError())) {
                log.warn("Order not found or MCP error: {}", orderId);
                return null;
            }
            if (result.structuredContent() != null) {
                return objectMapper.convertValue(result.structuredContent(), OrderInfo.class);
            }
            String text = extractText(result);
            Matcher matcher = JSON_OBJECT.matcher(text.trim());
            if (matcher.find()) {
                return objectMapper.readValue(matcher.group(), OrderInfo.class);
            }
            log.warn("Unable to parse order JSON from MCP result: {}", orderId);
            return null;
        } catch (Exception e) {
            log.error("MCP query_order failed for {}: {}", orderId, e.getMessage());
            return null;
        }
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
        try {
            McpSchema.CallToolResult result = orderMcpClient.callTool(
                    "modify_order_address",
                    Map.of("orderId", orderId, "newAddress", newAddress));
            return extractText(result);
        } catch (Exception e) {
            log.error("MCP modify_order_address failed for {}: {}", orderId, e.getMessage());
            return "修改地址失败：" + e.getMessage();
        }
    }

    private static String extractText(McpSchema.CallToolResult result) {
        List<McpSchema.Content> contents = result.content();
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : contents) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(textContent.text());
            }
        }
        return sb.toString();
    }
}
