package com.cs.order.mcp;

import com.cs.common.model.OrderInfo;
import com.cs.order.store.OrderStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 订单 MCP 工具定义：{@code query_order} / {@code modify_order_address}（Stateless）。
 */
@Component
@RequiredArgsConstructor
public class OrderMcpTools {

    private final OrderStore orderStore;
    private final ObjectMapper objectMapper;

    public List<McpStatelessServerFeatures.SyncToolSpecification> specifications() {
        return List.of(queryOrderSpec(), modifyAddressSpec());
    }

    private McpStatelessServerFeatures.SyncToolSpecification queryOrderSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("orderId", Map.of(
                "type", "string",
                "description", "订单号，例如 ORD20260609001"));

        Tool tool = Tool.builder()
                .name("query_order")
                .description("根据订单号查询订单详情、状态与物流信息。订单号通常以 ORD 开头。")
                .inputSchema(new JsonSchema("object", properties, List.of("orderId"), false, null, null))
                .annotations(new ToolAnnotations(null, true, false, true, false, null))
                .build();

        BiFunction<McpTransportContext, McpSchema.CallToolRequest, CallToolResult> handler =
                (ctx, request) -> {
                    String orderId = stringArg(request.arguments(), "orderId");
                    OrderInfo order = orderStore.findById(orderId);
                    if (order == null) {
                        return CallToolResult.builder()
                                .addTextContent("未找到订单：" + orderId)
                                .isError(true)
                                .build();
                    }
                    try {
                        String json = objectMapper.writeValueAsString(order);
                        String display = order.toDisplayText()
                                + (order.getLogisticsInfo() != null
                                ? "\n物流：" + order.getLogisticsInfo() : "");
                        return CallToolResult.builder()
                                .addTextContent(display + "\n\n" + json)
                                .structuredContent(order)
                                .isError(false)
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .addTextContent("订单序列化失败：" + e.getMessage())
                                .isError(true)
                                .build();
                    }
                };

        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    private McpStatelessServerFeatures.SyncToolSpecification modifyAddressSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("orderId", Map.of(
                "type", "string",
                "description", "订单号"));
        properties.put("newAddress", Map.of(
                "type", "string",
                "description", "新的收货地址"));

        Tool tool = Tool.builder()
                .name("modify_order_address")
                .description("修改未发货订单的收货地址。已发货订单不可修改。")
                .inputSchema(new JsonSchema(
                        "object", properties, List.of("orderId", "newAddress"), false, null, null))
                .annotations(new ToolAnnotations(null, false, true, false, false, null))
                .build();

        BiFunction<McpTransportContext, McpSchema.CallToolRequest, CallToolResult> handler =
                (ctx, request) -> {
                    String orderId = stringArg(request.arguments(), "orderId");
                    String newAddress = stringArg(request.arguments(), "newAddress");
                    String result = orderStore.modifyAddress(orderId, newAddress);
                    boolean error = "订单不存在".equals(result)
                            || result.contains("无法修改地址");
                    return CallToolResult.builder()
                            .addTextContent(result)
                            .isError(error)
                            .build();
                };

        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        if (args == null || args.get(key) == null) {
            return null;
        }
        return String.valueOf(args.get(key));
    }
}
