package io.meridian.merchantchannel.config;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.merchantchannel.application.CurrentUser;
import io.meridian.web.security.MerchantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every /v1/merchant request must come from a signed-in merchant user of a tenant whose app user record exists for that
 * merchant and is ACTIVE: staff, service, customer, agent, business and anonymous callers get 403
 * {@code merchant_identity_required} (or 401 {@code tenant_required}), unknown or inactive users 403, before any
 * controller runs. The verified user record is bound for the request ({@link CurrentUser}).
 */
@Configuration
@EnableConfigurationProperties(MerchantChannelProperties.class)
public class MerchantChannelConfig {

    @Bean
    WebMvcConfigurer merchantOnly(CurrentUser users) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                        MerchantContext.require();
                        TenantContext.require();
                        users.bind();
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler,
                                                Exception ex) {
                        CurrentUser.clear();
                    }
                }).addPathPatterns("/v1/merchant", "/v1/merchant/**");
            }
        };
    }
}
