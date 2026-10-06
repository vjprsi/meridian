package io.meridian.agentnetwork.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentNetworkProperties.class)
public class AgentNetworkConfig {
}
