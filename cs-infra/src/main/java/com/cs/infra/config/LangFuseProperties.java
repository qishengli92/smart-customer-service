package com.cs.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LangFuse 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.observability.langfuse")
public class LangFuseProperties {

    /**
     * 是否启用
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
     * LangFuse API 地址
     */
    private String baseUrl = "https://cloud.langfuse.com";

    /**
     * 发布开关（debug 模式下不发送到远端）
     */
    private boolean flushEnabled = true;
}
