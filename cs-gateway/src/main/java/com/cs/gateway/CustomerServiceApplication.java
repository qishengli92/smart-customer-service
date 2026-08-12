package com.cs.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 智能客服系统 Spring Boot 启动入口（MVP-1.0）。
 * <p>
 * 模块扫描 {@code com.cs.*}，装配链路：
 * {@code cs-gateway}（HTTP/SSE）→ {@code cs-orchestrator}（编排）→ {@code cs-agents}
 * （AgentScope ReActAgent）→ {@code cs-tools}/{@code cs-knowledge}/{@code cs-memory}，
 * 基础设施在 {@code cs-infra}（DashScope / LangFuse / Redis / PG / Milvus）。
 */
@SpringBootApplication(scanBasePackages = "com.cs")
@EntityScan(basePackages = "com.cs.infra.persistence.entity")
@EnableJpaRepositories(basePackages = "com.cs.infra.persistence.repo")
@EnableConfigurationProperties
@EnableAsync
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
        System.out.println("""

                ============================================
                  智能客服 Agent 系统 MVP-1.0 已启动
                  Web Chat: http://localhost:8080
                  SSE API:  http://localhost:8080/api/v1/chat/stream
                  Confirm:  POST /api/v1/chat/confirmations/{id}
                  Handoff:  /api/v1/handoff/**
                  Actuator: http://localhost:8080/actuator/health
                ============================================
                """);
    }
}
