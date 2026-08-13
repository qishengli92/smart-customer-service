package com.cs.infra.observability.otel;

import com.cs.common.util.JsonUtils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 AgentScope Msg / Tool 序列化为 LangFuse 可识别的 GenAI OTEL 属性 JSON。
 */
@Slf4j
final class GenAiAttributeHelper {

    private static final int MAX_FIELD_CHARS = 8000;

    private GenAiAttributeHelper() {}

    static String inputMessagesJson(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            if (msg == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", msg.getRole() != null ? msg.getRole().name().toLowerCase() : "user");
            if (msg.getName() != null && !msg.getName().isBlank()) {
                item.put("name", msg.getName());
            }
            item.put("parts", toParts(msg.getContent()));
            out.add(item);
        }
        return toJsonQuiet(out);
    }

    static String outputMessagesJson(Msg msg) {
        if (msg == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", msg.getRole() != null ? msg.getRole().name().toLowerCase() : "assistant");
        item.put("parts", toParts(msg.getContent()));
        return toJsonQuiet(List.of(item));
    }

    static String outputMessagesJson(ChatResponse response) {
        if (response == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", "assistant");
        item.put("parts", toParts(response.getContent()));
        return toJsonQuiet(List.of(item));
    }

    static String toolDefinitionsJson(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> defs = new ArrayList<>(tools.size());
        for (ToolSchema schema : tools) {
            if (schema == null) {
                continue;
            }
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", schema.getName());
            def.put("description", truncate(schema.getDescription()));
            if (schema.getParameters() != null && !schema.getParameters().isEmpty()) {
                def.put("parameters", schema.getParameters());
            }
            defs.add(def);
        }
        return toJsonQuiet(defs);
    }

    static String toolArgumentsJson(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        return toJsonQuiet(truncateMap(input));
    }

    static String toolResultJson(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock text && text.getText() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text.getText());
            } else if (block != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(block);
            }
        }
        return truncate(sb.toString());
    }

    private static List<Map<String, Object>> toParts(List<ContentBlock> content) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (content == null) {
            return parts;
        }
        for (ContentBlock block : content) {
            if (block instanceof TextBlock text) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "text");
                part.put("content", truncate(text.getText()));
                parts.add(part);
            } else if (block instanceof ThinkingBlock thinking) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "reasoning");
                part.put("content", truncate(thinking.getThinking()));
                parts.add(part);
            } else if (block instanceof ToolUseBlock toolUse) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "tool_call");
                part.put("id", toolUse.getId());
                part.put("name", toolUse.getName());
                part.put("arguments", truncateMap(toolUse.getInput()));
                parts.add(part);
            } else if (block instanceof ToolResultBlock toolResult) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "tool_call_response");
                part.put("id", toolResult.getId());
                part.put("name", toolResult.getName());
                part.put("result", toolResultJson(toolResult.getOutput()));
                parts.add(part);
            }
        }
        return parts;
    }

    private static Map<String, Object> truncateMap(Map<String, Object> input) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                copy.put(e.getKey(), truncate(s));
            } else {
                copy.put(e.getKey(), v);
            }
        }
        return copy;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_FIELD_CHARS ? value.substring(0, MAX_FIELD_CHARS) + "..." : value;
    }

    private static String toJsonQuiet(Object value) {
        try {
            return JsonUtils.toJson(value);
        } catch (Exception e) {
            log.warn("GenAI attribute serialize failed: {}", e.getMessage());
            return null;
        }
    }
}
