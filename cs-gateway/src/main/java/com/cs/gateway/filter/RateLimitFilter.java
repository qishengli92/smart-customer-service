package com.cs.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * 接入层简易限流（MVP：进程内窗口计数）。
 * <p>
 * 生产应换 Redis + Lua 令牌桶；位置在 TraceFilter 之后，保护编排与 LLM 调用。
 */
@Slf4j
@Component
@Order(2)
public class RateLimitFilter implements WebFilter {

    /**
     * 每秒最大请求数
     */
    private static final int MAX_REQUESTS_PER_SECOND = 50;

    /**
     * 窗口大小（毫秒）
     */
    private static final long WINDOW_MS = 1000;

    /**
     * 当前窗口计数
     */
    private int currentCount = 0;

    /**
     * 窗口起始时间
     */
    private volatile long windowStart = System.currentTimeMillis();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 只对聊天接口限流
        if (!path.startsWith("/api/v1/chat/stream")) {
            return chain.filter(exchange);
        }

        long now = System.currentTimeMillis();
        if (now - windowStart >= WINDOW_MS) {
            windowStart = now;
            currentCount = 0;
        }

        currentCount++;

        if (currentCount > MAX_REQUESTS_PER_SECOND) {
            log.warn("Rate limit exceeded: path={}, currentCount={}", path, currentCount);
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }
}
