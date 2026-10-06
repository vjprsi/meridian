package io.meridian.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * OAuth2 scope an operation requires, e.g. {@code ledger:write}. Generated on every API interface method from
 * the spec; enforced by {@link ScopeInterceptor} in JWT mode. {@code <domain>:*} and {@code meridian:admin}
 * satisfy any scope of that domain / of all domains.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiredScope {
    String value();
}
