package io.meridian.inbox.api;

import io.meridian.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Deny by default for customer callers: a relayed customer may only use the operations listed here (each of which
 * checks ownership itself). Everything else (assignment, thread edits, the message and receipt resources,
 * announcement management) is bank-side only: 403 staff_only.
 */
@Configuration
public class CustomerAccess implements WebMvcConfigurer {
    static final Set<String> CUSTOMER_OPERATIONS = Set.of(
            "secureMessageThreadsList", "secureMessageThreadsGet", "secureMessageThreadsCreate",
            "secureMessageThreadsMessages", "secureMessageThreadsAddMessages", "secureMessageThreadsRead",
            "secureMessageThreadsClose", "secureMessageThreadsReopen",
            "announcementsActive", "announcementsDismiss");

    private final Callers callers;

    public CustomerAccess(Callers callers) {
        this.callers = callers;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (handler instanceof HandlerMethod hm && callers.current().customer()
                        && !CUSTOMER_OPERATIONS.contains(hm.getMethod().getName())) {
                    throw new ApiException(403, "staff_only", "This operation is available to bank staff only");
                }
                return true;
            }
        }).addPathPatterns("/v1/inbox/**");
    }
}
