package io.meridian.loanorigination.config;

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

/**
 * Service configuration. Keeps the raw request body of the public provider callbacks (e-signature, bureau) after
 * JSON binding: their HMAC signature covers the exact bytes received, which re-serialising the model would not
 * reproduce.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OriginationProperties.class)
public class OriginationConfig {
    private static final int LIMIT = 1024 * 1024;

    /** Deterministic sandbox bureau (synchronous). */
    @Bean
    io.meridian.loanorigination.application.BureauAdapter sandboxBureau() {
        return new io.meridian.loanorigination.infrastructure.SandboxBureauAdapter("SANDBOX", false);
    }

    /** Deterministic sandbox bureau that answers through the asynchronous callback. */
    @Bean
    io.meridian.loanorigination.application.BureauAdapter sandboxAsyncBureau() {
        return new io.meridian.loanorigination.infrastructure.SandboxBureauAdapter("SANDBOX_ASYNC", true);
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> lendingCallbackRawBodyFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(new ContentCachingRequestWrapper(req, LIMIT), res);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/v1/e-signature-requests/callbacks/*", "/v1/bureau-reports/callbacks/*");
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
