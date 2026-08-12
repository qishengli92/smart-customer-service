package com.cs.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * 请求追踪 + Reactor Context 注入租户/用户（禁止 ThreadLocal）
 */
@Slf4j
@Component
@Order(1)
public class TraceFilter implements WebFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String USER_HEADER = "X-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null) {
            requestId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();
        String tenantId = headers.getFirst(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        String userId = headers.getFirst(USER_HEADER);
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }

        exchange.getAttributes().put("requestId", requestId);
        exchange.getAttributes().put("tenantId", tenantId);
        exchange.getAttributes().put("userId", userId);

        exchange.getResponse().getHeaders().add(TRACE_HEADER, requestId);
        exchange.getResponse().getHeaders().add(REQUEST_ID_HEADER, requestId);

        log.debug("Request: method={}, path={}, requestId={}, tenant={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                requestId,
                tenantId);

        String finalTenantId = tenantId;
        String finalUserId = userId;
        String finalRequestId = requestId;
        return chain.filter(exchange)
                .contextWrite(Context.of(
                        "tenantId", finalTenantId,
                        "userId", finalUserId,
                        "requestId", finalRequestId));
    }
}
