package com.cs.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LangFuse 双轨可观测配置（{@code cs.observability.langfuse}）。
 * <ul>
 *   <li>Track A · ingestion：编排层 Trace/Span（会话、Router、Agent）</li>
 *   <li>Track B · OTLP：AgentScope {@code TracerRegistry} 上报 LLM Prompt / Tool（GenAI 语义）</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.observability.langfuse")
public class LangFuseProperties {

    /**
     * 总开关
     */
    private boolean enabled = true;

    /**
     * LangFuse 公钥
     */
    private String publicKey;

    /**
     * LangFuse 密钥
     */
    private String secretKey;

    /**
     * LangFuse API 地址（不含 path）
     */
    private String baseUrl = "https://cloud.langfuse.com";

    /**
     * Track A：是否经 /api/public/ingestion 上报业务 Trace
     */
    private boolean flushEnabled = true;

    /**
     * Track B：是否启用 OTLP GenAI（Prompt / Tool 完整数据）
     */
    private boolean otelEnabled = true;
}
