package com.cs.gateway.adapter;

import com.cs.common.model.StreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * {@link StreamEvent} → HTTP SSE 协议适配（EventSource 可直接消费）。
 * <p>
 * 当前 ChatController 也可自行 {@code map}；本类供复用或非 Controller 推流场景。
 */
@Slf4j
@Component
public class SseAdapter {

    /**
     * 将 StreamEvent Flux 转换为 SSE Flux
     *
     * @param eventFlux 内部事件流
     * @param sessionId 会话ID（附加在首个事件中）
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> adapt(Flux<StreamEvent> eventFlux, String sessionId) {
        return eventFlux.map(event -> {
            String jsonPayload = formatPayload(event, sessionId);
            return ServerSentEvent.<String>builder()
                    .event(event.getType())
                    .data(jsonPayload)
                    .build();
        });
    }

    /**
     * 格式化事件载荷
     */
    private String formatPayload(StreamEvent event, String sessionId) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"").append(event.getType()).append("\"");

        if (event.getData() != null) {
            sb.append(",\"data\":").append(escapeJsonString(event.getData()));
        }
        if (event.getAgentName() != null) {
            sb.append(",\"agentName\":\"").append(event.getAgentName()).append("\"");
        }
        if (sessionId != null) {
            sb.append(",\"sessionId\":\"").append(sessionId).append("\"");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJsonString(String value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        escaped.append("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        escaped.append("\"");
        return escaped.toString();
    }
}
