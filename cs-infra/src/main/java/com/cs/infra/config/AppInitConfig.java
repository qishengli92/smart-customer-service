package com.cs.infra.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用初始化配置
 * <p>
 * 系统启动后的初始化操作：Milvus Collection 创建、知识库预热等。
 */
@Configuration
public class AppInitConfig {

    /**
     * 启动后打印系统信息
     */
    @Bean
    public CommandLineRunner systemInfoPrinter() {
        return args -> {
            System.out.println("""
                    
                    ╔══════════════════════════════════════════════╗
                    ║   智能客服 Agent 系统 MVP-1.0              ║
                    ║   Framework: AgentScope Java 2.0           ║
                    ║   LLM: DashScope (Qwen) · 规则 Router      ║
                    ║   Observability: LangFuse                   ║
                    ║   DB: PostgreSQL + Redis + Milvus FAQ Seed ║
                    ╚══════════════════════════════════════════════╝
                    """);
        };
    }
}
