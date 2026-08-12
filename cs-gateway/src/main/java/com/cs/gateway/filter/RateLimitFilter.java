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
 * 限流过滤器
 * <p>
 * 基于 Redis 令牌桶的简易限流（V1.5 阶段使用内存计数器）。
 * 生产环境需替换为 Redis + Lua 脚本实现。
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
