package com.cs.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 领域 Agent 处理结果：普通回复或挂起确认
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentHandleResult {

    private String replyText;
    private PendingAction pendingAction;
    private boolean handoffRequested;
    private HandoffRecord handoffRecord;

    public static AgentHandleResult text(String replyText) {
        return AgentHandleResult.builder().replyText(replyText).build();
    }

    public static AgentHandleResult pending(PendingAction pending, String promptText) {
        return AgentHandleResult.builder()
                .pendingAction(pending)
                .replyText(promptText)
                .build();
    }

    public static AgentHandleResult handoff(String replyText, HandoffRecord record) {
        return AgentHandleResult.builder()
                .replyText(replyText)
                .handoffRequested(true)
                .handoffRecord(record)
                .build();
    }

    public static AgentHandleResult handoff(String replyText) {
        return handoff(replyText, null);
    }

    public boolean hasPending() {
        return pendingAction != null;
    }
}
