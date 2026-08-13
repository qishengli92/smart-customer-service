package com.cs.infra.observability.otel;

import com.cs.infra.config.LangFuseProperties;
import io.agentscope.core.tracing.TracerRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Track B：初始化 OTLP → LangFuse，并注册 {@link GenAiOtelTracer} 到 AgentScope {@link TracerRegistry}。
 * <p>
 * 与 Track A（{@code LangFuseTracer} ingestion）并行：业务编排走 ingestion，LLM/Tool 走 GenAI OTEL。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class LangFuseOtelConfig implements ApplicationRunner {

    private static final String INSTRUMENTATION_NAME = "smart-cs-agentscope";
    private static final AtomicBoolean REACTOR_HOOK_REGISTERED = new AtomicBoolean(false);

    private final LangFuseProperties properties;
    private volatile SdkTracerProvider tracerProvider;
    private volatile boolean registered;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled() || !properties.isOtelEnabled()) {
            log.info("LangFuse OTLP track disabled (enabled={}, otelEnabled={})",
                    properties.isEnabled(), properties.isOtelEnabled());
            return;
        }
        String pk = properties.getPublicKey();
        String sk = properties.getSecretKey();
        if (pk == null || sk == null || pk.contains("your-public") || sk.contains("your-secret")) {
            log.warn("LangFuse OTLP keys not configured, skip GenAI tracer registration");
            return;
        }

        try {
            ensureReactorPropagation();
            String endpoint = trimSlash(properties.getBaseUrl()) + "/api/public/otel/v1/traces";
            String auth = Base64.getEncoder().encodeToString(
                    (pk + ":" + sk).getBytes(StandardCharsets.UTF_8));

            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .addHeader("Authorization", "Basic " + auth)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .build();

            Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                    AttributeKey.stringKey("service.name"), "smart-customer-service")));

            tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .setSampler(Sampler.alwaysOn())
                    .setResource(resource)
                    .build();

            OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
            GlobalOpenTelemetry.set(otel);

            GenAiOtelTracer genAiTracer = new GenAiOtelTracer(
                    otel.getTracer(INSTRUMENTATION_NAME),
                    tracerProvider::close);
            TracerRegistry.register(genAiTracer);
            registered = true;
            log.info("LangFuse dual-track ready: OTLP GenAI → {}", endpoint);
        } catch (Exception e) {
            log.error("Failed to init LangFuse OTLP track: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!registered) {
            return;
        }
        try {
            TracerRegistry.resetToNoop();
            registered = false;
            tracerProvider = null;
        } catch (Exception e) {
            log.warn("LangFuse OTLP shutdown failed: {}", e.getMessage());
        }
    }

    private void ensureReactorPropagation() {
        if (REACTOR_HOOK_REGISTERED.compareAndSet(false, true)) {
            ContextPropagationOperator.builder().build().registerOnEachOperator();
        }
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://cloud.langfuse.com";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
