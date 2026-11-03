package io.meridian.agentnetwork.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentNetworkProperties.class)
@EnableScheduling   // WE-FLOAT-1: auto-deallocation sweep planner (gated, plan-only; a checker still approves)
public class AgentNetworkConfig {
}
