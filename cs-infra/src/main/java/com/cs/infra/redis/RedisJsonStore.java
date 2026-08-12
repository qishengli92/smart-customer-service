package com.cs.infra.redis;

import com.cs.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis JSON 同步读写封装：会话 / PendingAction / Handoff 等热数据底座。
 * <p>
 * API 为阻塞式，调用方应在 {@code boundedElastic} 执行。
 */
@Slf4j
@Component
public class RedisJsonStore {

    @Autowired(required = false)
    @Qualifier("reactiveStringRedisTemplate")
    private ReactiveRedisTemplate<String, String> reactiveRedis;

    public boolean available() {
        return reactiveRedis != null;
    }

    public void set(String key, Object value, Duration ttl) {
        if (reactiveRedis == null) {
            return;
        }
        try {
            reactiveRedis.opsForValue()
                    .set(key, JsonUtils.toJson(value), ttl)
                    .block(Duration.ofSeconds(2));
        } catch (Exception e) {
            log.warn("Redis set failed key={}: {}", key, e.getMessage());
        }
    }

    public void setString(String key, String value, Duration ttl) {
        if (reactiveRedis == null) {
            return;
        }
        try {
            reactiveRedis.opsForValue().set(key, value, ttl).block(Duration.ofSeconds(2));
        } catch (Exception e) {
            log.warn("Redis setString failed key={}: {}", key, e.getMessage());
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        if (reactiveRedis == null) {
            return Optional.empty();
        }
        try {
            String json = reactiveRedis.opsForValue().get(key).block(Duration.ofSeconds(2));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(JsonUtils.fromJson(json, type));
        } catch (Exception e) {
            log.warn("Redis get failed key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> getString(String key) {
        if (reactiveRedis == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    reactiveRedis.opsForValue().get(key).block(Duration.ofSeconds(2)));
        } catch (Exception e) {
            log.warn("Redis getString failed key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String key) {
        if (reactiveRedis == null) {
            return;
        }
        try {
            reactiveRedis.delete(key).block(Duration.ofSeconds(2));
        } catch (Exception e) {
            log.warn("Redis delete failed key={}: {}", key, e.getMessage());
        }
    }
}
