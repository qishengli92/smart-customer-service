package com.cs.infra.observability.otel;

import io.agentscope.core.agent.accumulator.TextAccumulator;
import io.agentscope.core.agent.accumulator.ThinkingAccumulator;
import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合流式 {@link ChatResponse}，供 OTEL Generation 写入完整 output / usage。
 */
final class ChatResponseAggregator {

    private String id;
    private final TextAccumulator textAcc = new TextAccumulator();
    private final ThinkingAccumulator thinkingAcc = new ThinkingAccumulator();
    private final ToolCallsAccumulator toolCallsAcc = new ToolCallsAccumulator();
    private int inputTokens;
    private int outputTokens;
    private double time;
    private String finishReason;

    void append(ChatResponse chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.getId() != null) {
            id = chunk.getId();
        }
        List<ContentBlock> contents = chunk.getContent();
        if (contents != null) {
            for (ContentBlock block : contents) {
                if (block instanceof TextBlock tb) {
                    textAcc.add(tb);
                } else if (block instanceof ThinkingBlock tb) {
                    thinkingAcc.add(tb);
                } else if (block instanceof ToolUseBlock tub) {
                    toolCallsAcc.add(tub);
                }
            }
        }
        ChatUsage usage = chunk.getUsage();
        if (usage != null) {
            inputTokens = Math.max(inputTokens, usage.getInputTokens());
            outputTokens = Math.max(outputTokens, usage.getOutputTokens());
            time = usage.getTime();
        }
        if (chunk.getFinishReason() != null) {
            finishReason = chunk.getFinishReason();
        }
    }

    ChatResponse getResponse() {
        List<ToolUseBlock> toolUses = toolCallsAcc.buildAllToolCalls();
        List<ContentBlock> content = new ArrayList<>(toolUses.size() + 2);
        content.add(textAcc.buildAggregated());
        content.add(thinkingAcc.buildAggregated());
        content.addAll(toolUses);
        return ChatResponse.builder()
                .id(id)
                .content(content)
                .usage(ChatUsage.builder()
                        .inputTokens(inputTokens)
                        .outputTokens(outputTokens)
                        .time(time)
                        .build())
                .finishReason(finishReason)
                .build();
    }
}
