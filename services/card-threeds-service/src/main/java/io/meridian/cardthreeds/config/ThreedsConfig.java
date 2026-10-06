package io.meridian.cardthreeds.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

/** Settings plus raw-body caching for the public AReq endpoint, whose HMAC signature covers the exact bytes received. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CardsProperties.class)
public class ThreedsConfig {
    private static final int LIMIT = 256 * 1024;

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> rawBodyFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(new ContentCachingRequestWrapper(req, LIMIT), res);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/v1/threeds-authentications/authenticate");
        return reg;
    }

    /** Raw bytes of the current request body (empty if it was not cached). */
    public static byte[] currentBody() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            ContentCachingRequestWrapper w = WebUtils.getNativeRequest(a.getRequest(), ContentCachingRequestWrapper.class);
            if (w != null) {
                return w.getContentAsByteArray();
            }
        }
        return new byte[0];
    }
}
