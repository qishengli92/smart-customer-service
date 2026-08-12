package com.cs.knowledge.milvus;

import com.cs.infra.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus Java Client Bean 装配（连接参数来自 {@link com.cs.infra.config.MilvusProperties}）。
 */
@Slf4j
@Configuration
public class MilvusClientConfig {

    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient(MilvusProperties properties) {
        log.info("Connecting Milvus {}:{}", properties.getHost(), properties.getPort());
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(properties.getHost())
                        .withPort(properties.getPort())
                        .build()
        );
    }
}
