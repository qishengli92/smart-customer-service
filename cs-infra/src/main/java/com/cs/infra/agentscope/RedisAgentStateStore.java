package com.cs.infra.agentscope;

import com.cs.infra.redis.RedisJsonStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentScope 2.0 短期记忆持久化：实现原生 {@link AgentStateStore}。
 * <p>
 * 优先 Redis（跨副本共享会话上下文），不可用时回退进程内 Map。
 * ReActAgent 在每次 {@code call(msgs, RuntimeContext)} 结束时自动
 * {@code save(userId, sessionId, "agent_state", state)}，下次同槽位自动恢复
 * {@link AgentState#getContext()} 对话历史。
 */
@Slf4j
public class RedisAgentStateStore implements AgentStateStore {

    private static final String ANON = "__anon__";
    private static final String KEY_PREFIX = "cs:as:state:";
    private static final String INDEX_PREFIX = "cs:as:sessions:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisJsonStore redisJsonStore;
    /** local: userId -> (sessionId -> (key -> json)) */
    private final Map<String, Map<String, Map<String, String>>> local = new ConcurrentHashMap<>();

    public RedisAgentStateStore(RedisJsonStore redisJsonStore) {
        this.redisJsonStore = redisJsonStore;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(key, "key");
        String json = serialize(value);
        String rk = redisKey(userId, sessionId, key);
        if (redisJsonStore != null && redisJsonStore.available()) {
            redisJsonStore.setString(rk, json, TTL);
            indexSession(userId, sessionId);
        }
        local.computeIfAbsent(normUser(userId), u -> new ConcurrentHashMap<>())
                .computeIfAbsent(sessionId, s -> new ConcurrentHashMap<>())
                .put(key, json);
        log.trace("AgentState saved: user={}, session={}, key={}", userId, sessionId, key);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(key, "key");
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (State v : values) {
                parts.add(serialize(v));
            }
        }
        // 用换行分隔的 JSON 数组包装，便于 getList 还原
        String json = "[" + String.join(",", parts) + "]";
        String rk = redisKey(userId, sessionId, key);
        if (redisJsonStore != null && redisJsonStore.available()) {
            redisJsonStore.setString(rk, json, TTL);
            indexSession(userId, sessionId);
        }
        local.computeIfAbsent(normUser(userId), u -> new ConcurrentHashMap<>())
                .computeIfAbsent(sessionId, s -> new ConcurrentHashMap<>())
                .put(key, json);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(key, "key");
        String json = readRaw(userId, sessionId, key);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            if (AgentState.class.isAssignableFrom(type)) {
                return Optional.of((T) AgentState.fromJsonString(json));
            }
            log.warn("Unsupported AgentStateStore type: {}", type.getName());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to deserialize AgentState: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        // MVP：列表态较少使用；单值场景走 get()
        return Collections.emptyList();
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Map<String, Map<String, String>> bySession = local.get(normUser(userId));
        if (bySession != null && bySession.containsKey(sessionId) && !bySession.get(sessionId).isEmpty()) {
            return true;
        }
        // Redis：探测 agent_state 键
        if (redisJsonStore != null && redisJsonStore.available()) {
            return redisJsonStore.getString(redisKey(userId, sessionId, "agent_state")).isPresent();
        }
        return false;
    }

    @Override
    public void delete(String userId, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Map<String, Map<String, String>> bySession = local.get(normUser(userId));
        if (bySession != null) {
            Map<String, String> keys = bySession.remove(sessionId);
            if (keys != null && redisJsonStore != null && redisJsonStore.available()) {
                for (String k : keys.keySet()) {
                    redisJsonStore.delete(redisKey(userId, sessionId, k));
                }
            }
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Set<String> ids = new HashSet<>();
        Map<String, Map<String, String>> bySession = local.get(normUser(userId));
        if (bySession != null) {
            ids.addAll(bySession.keySet());
        }
        if (redisJsonStore != null && redisJsonStore.available()) {
            redisJsonStore.getString(INDEX_PREFIX + normUser(userId)).ifPresent(raw -> {
                for (String s : raw.split(",")) {
                    if (!s.isBlank()) {
                        ids.add(s.trim());
                    }
                }
            });
        }
        return ids;
    }

    private String readRaw(String userId, String sessionId, String key) {
        if (redisJsonStore != null && redisJsonStore.available()) {
            Optional<String> fromRedis = redisJsonStore.getString(redisKey(userId, sessionId, key));
            if (fromRedis.isPresent()) {
                // 回填本地缓存
                local.computeIfAbsent(normUser(userId), u -> new ConcurrentHashMap<>())
                        .computeIfAbsent(sessionId, s -> new ConcurrentHashMap<>())
                        .put(key, fromRedis.get());
                return fromRedis.get();
            }
        }
        Map<String, Map<String, String>> bySession = local.get(normUser(userId));
        if (bySession == null) {
            return null;
        }
        Map<String, String> keys = bySession.get(sessionId);
        return keys == null ? null : keys.get(key);
    }

    private void indexSession(String userId, String sessionId) {
        String indexKey = INDEX_PREFIX + normUser(userId);
        Set<String> ids = new HashSet<>(listSessionIds(userId));
        ids.add(sessionId);
        redisJsonStore.setString(indexKey, String.join(",", ids), TTL);
    }

    private static String serialize(State value) {
        if (value instanceof AgentState agentState) {
            return agentState.toJson();
        }
        throw new IllegalArgumentException("Unsupported State type: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static String redisKey(String userId, String sessionId, String key) {
        return KEY_PREFIX + normUser(userId) + ":" + sessionId + ":" + key;
    }

    private static String normUser(String userId) {
        return userId == null || userId.isBlank() ? ANON : userId;
    }
}
