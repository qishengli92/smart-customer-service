package com.cs.order.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 MCP Stateless HTTP 传输（{@code /mcp}）与订单工具。
 * <p>
 * 使用 Stateless 避免 Streamable HTTP 长会话断开后出现
 * {@code MCP session with server terminated}。
 */
@Slf4j
@Configuration
public class OrderMcpServerConfig {

    private McpStatelessSyncServer mcpSyncServer;

    @Bean
    public HttpServletStatelessServerTransport mcpTransportProvider() {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(McpJsonMapper.getDefault())
                .messageEndpoint("/mcp")
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
            HttpServletStatelessServerTransport transportProvider) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> bean =
                new ServletRegistrationBean<>(transportProvider, "/mcp");
        bean.setName("mcpStatelessServlet");
        bean.setAsyncSupported(true);
        bean.setLoadOnStartup(1);
        return bean;
    }

    @Bean
    public McpStatelessSyncServer mcpSyncServer(
            HttpServletStatelessServerTransport transportProvider,
            OrderMcpTools orderMcpTools) {
        this.mcpSyncServer = McpServer.sync(transportProvider)
                .serverInfo("cs-order-service", "1.5.0")
                .instructions("订单中心 MCP（Stateless）：查询订单详情与修改未发货订单地址。")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(orderMcpTools.specifications())
                .build();
        log.info("Order MCP (stateless) server started at /mcp (tools: query_order, modify_order_address)");
        return this.mcpSyncServer;
    }

    @PreDestroy
    public void shutdown() {
        if (mcpSyncServer != null) {
            mcpSyncServer.close();
            log.info("Order MCP server closed");
        }
    }
}
