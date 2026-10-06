package io.meridian.treasurydeal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TreasuryProperties.class)
public class TreasuryConfig {
}
