package io.meridian.customerchannel.config;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.security.CustomerContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every /v1/me request must come from a signed-in customer of a tenant: staff, service and anonymous callers get
 * 403 {@code customer_identity_required} (or 401 {@code tenant_required}) before any controller runs.
 */
@Configuration
@EnableConfigurationProperties(ChannelProperties.class)
public class ChannelConfig {

    @Bean
    WebMvcConfigurer customerOnly() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                        CustomerContext.require();
                        TenantContext.require();
                        return true;
                    }
                }).addPathPatterns("/v1/me", "/v1/me/**");
            }
        };
    }
}
