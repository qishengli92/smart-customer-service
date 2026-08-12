package com.cs.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 全局配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.agent")
public class AgentProperties {

    /**
     * ReAct 最大迭代次数
     */
    private int maxIterations = 10;

    /**
     * 是否启用安全中断
     */
    private boolean safeInterruption = true;

    /**
     * 是否启人在环
     */
    private boolean humanInTheLoop = true;

    /**
     * 短期记忆最大轮数
     */
    private int shortTermMaxRounds = 20;

    /**
     * 退款风控阈值（元）
     */
    private double refundThreshold = 500;

    /**
     * 赔偿风控阈值（元）
     */
    private double compensationThreshold = 1000;
}
