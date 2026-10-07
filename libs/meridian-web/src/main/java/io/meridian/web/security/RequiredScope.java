package io.meridian.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * OAuth2 scope an operation requires, e.g. {@code ledger:write}. Generated on every API interface method from
 * the spec; enforced by {@link ScopeInterceptor} in JWT mode. {@code <domain>:*} and {@code meridian:admin}
 * satisfy any scope of that domain / of all domains. {@link #anyOf()} lists scopes accepted instead (spec {@code scopes:}
 * override, e.g. the cell router's {@code platform:routing-read} with {@code platform:operator} still accepted).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiredScope {
    String value();

    /** Scopes accepted instead of {@link #value()} (any one of them suffices). */
    String[] anyOf() default {};
}
