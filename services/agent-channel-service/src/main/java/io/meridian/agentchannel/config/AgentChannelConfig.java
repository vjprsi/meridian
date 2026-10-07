package io.meridian.agentchannel.config;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.security.AgentContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every /v1/agent request must come from a signed-in agent of a tenant: staff, service, customer and anonymous callers
 * get 403 {@code agent_identity_required} (or 401 {@code tenant_required}) before any controller runs.
 */
@Configuration
@EnableConfigurationProperties(AgentChannelProperties.class)
public class AgentChannelConfig {

    @Bean
    WebMvcConfigurer agentOnly() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                        AgentContext.require();
                        TenantContext.require();
                        return true;
                    }
                }).addPathPatterns("/v1/agent", "/v1/agent/**");
            }
        };
    }
}
