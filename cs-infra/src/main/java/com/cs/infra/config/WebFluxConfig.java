package com.cs.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.function.BodyInserters;

/**
 * WebFlux 配置。
 * <p>
 * 不要使用 {@code @EnableWebFlux}：会关闭 Boot 自动配置（含静态资源），导致 / 返回 404。
 */
@Configuration
public class WebFluxConfig implements WebFluxConfigurer {

    /**
     * 聊天页：/ 与 /index.html
     */
    @Bean
    public RouterFunction<ServerResponse> indexRouter() {
        return RouterFunctions.route()
                .GET("/", request -> htmlIndex())
                .GET("/index.html", request -> htmlIndex())
                .build();
    }

    private static reactor.core.publisher.Mono<ServerResponse> htmlIndex() {
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(BodyInserters.fromResource(new ClassPathResource("static/index.html")));
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
