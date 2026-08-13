package com.cs.infra.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动期基础设施初始化（如 Milvus collection 就绪检查）；与 KnowledgeSeedRunner 配合。
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
                    ║   Observability: LangFuse 双轨             ║
                    ║     A=ingestion编排 · B=OTLP GenAI         ║
                    ║   DB: PostgreSQL + Redis + Milvus FAQ Seed ║
                    ╚══════════════════════════════════════════════╝
                    """);
        };
    }
}
