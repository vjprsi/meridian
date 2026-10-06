package io.meridian.cardauthorization.config;

import io.meridian.cardauthorization.domain.AuthDecisionEngine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    @Bean
    AuthDecisionEngine authDecisionEngine() {
        return new AuthDecisionEngine();
    }

    /** Virtual threads for time-boxed downstream calls (fraud scoring) on the authorisation hot path. */
    @Bean(destroyMethod = "close")
    ExecutorService authCallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
