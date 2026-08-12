package com.cs.agents.complaint;

import com.cs.common.model.ComplaintTicket;
import com.cs.tools.ticket.ComplaintTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 投诉处理 Agent（MVP 规则模板，未接 ReActAgent）。
 * <p>
 * Router 将 {@code COMPLAINT} 映射到 {@code HUMAN_SERVICE}，本类偏预留；
 * 工单由 {@link ComplaintTool} 创建，高严重度自动升级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplaintAgent {

    private final ComplaintTool complaintTool;

    private static final String SYSTEM_PROMPT = """
            你是投诉处理专员，具备出色的情绪安抚和问题解决能力。
            原则：
            1. 先共情，后解决——用户情绪优先
            2. HIGH/CRITICAL 级别投诉必须升级处理
            3. 涉及赔偿需风控审核
            4. 保留完整处理记录，供后续追溯
            """;

    /**
     * 处理投诉
     */
    public String handle(String userMessage, String context) {
        log.info("ComplaintAgent handling: {}", userMessage.substring(0, Math.min(50, userMessage.length())));

        // 判断严重程度
        String severity = assessSeverity(userMessage);

        // 创建投诉工单
        String orderId = extractOrderId(userMessage);
        ComplaintTicket ticket = complaintTool.createComplaint(userMessage, severity, orderId);

        // 根据严重程度给出不同响应
        return switch (severity) {
            case "CRITICAL", "HIGH" -> {
                // 自动升级
                complaintTool.escalateComplaint(ticket.getTicketId(), "自动升级：严重程度=" + severity, "MANAGER");
                yield String.format("""
                        非常抱歉给您带来不好的体验，我完全理解您的心情。🙏
                        
                        您的投诉已记录并自动升级至高级处理：
                        📋 工单号：%s
                        ⚠️ 严重程度：%s
                        👤 已分配至：主管团队
                        
                        我们的客服主管将在30分钟内与您联系，确保问题得到妥善解决。
                        再次向您致歉！
                        """,
                        ticket.getTicketId(), severity);
            }
            case "MEDIUM" -> String.format("""
                    非常抱歉给您带来不便，我理解您的不满。
                    
                    您的投诉已记录：
                    📋 工单号：%s
                    📌 严重程度：中等
                    👤 处理人：正在分配
                    
                    我们会在2小时内联系您处理，感谢您的耐心。
                    """,
                    ticket.getTicketId());
            default -> String.format("""
                    感谢您的反馈，我们会认真对待。
                    
                    📋 工单号：%s
                    📌 我们会尽快为您处理，如有进展会及时通知您。
                    """,
                    ticket.getTicketId());
        };
    }

    private String assessSeverity(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("严重") || lower.contains("恶劣") || lower.contains("投诉到")
                || lower.contains("消协") || lower.contains("律师")) {
            return "CRITICAL";
        }
        if (lower.contains("非常不满") || lower.contains("太差") || lower.contains("欺骗")
                || lower.contains("赔偿")) {
            return "HIGH";
        }
        if (lower.contains("不满") || lower.contains("失望") || lower.contains("差评")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String extractOrderId(String message) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("ORD\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group() : null;
    }
}
