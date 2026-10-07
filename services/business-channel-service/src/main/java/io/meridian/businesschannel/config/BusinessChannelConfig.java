package io.meridian.businesschannel.config;

import io.meridian.businesschannel.application.CurrentUser;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.security.BusinessContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every /v1/business request must come from a signed-in business user of a tenant whose portal user record exists for
 * that business and is ACTIVE: staff, service, customer, agent and anonymous callers get 403
 * {@code business_identity_required} (or 401 {@code tenant_required}), unknown or inactive users 403, before any
 * controller runs. The verified user record is bound for the request ({@link CurrentUser}).
 */
@Configuration
@EnableConfigurationProperties(BusinessChannelProperties.class)
public class BusinessChannelConfig {

    @Bean
    WebMvcConfigurer businessOnly(CurrentUser users) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                        BusinessContext.require();
                        TenantContext.require();
                        users.bind();
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler,
                                                Exception ex) {
                        CurrentUser.clear();
                    }
                }).addPathPatterns("/v1/business", "/v1/business/**");
            }
        };
    }
}
