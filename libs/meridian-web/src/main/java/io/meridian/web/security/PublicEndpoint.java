package io.meridian.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SEC-29: marks a hand-written {@code /v1} handler as intentionally callable without a scope. In JWT mode
 * {@link ScopeInterceptor} refuses every {@code /v1} handler that has neither {@link RequiredScope} nor this marker,
 * unless the request is a spec-{@code [public]} route (provider callbacks, the onboarding applicant surface).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicEndpoint {
}
