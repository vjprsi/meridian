package io.meridian.cardvault.api;

import io.meridian.common.error.ApiException;
import io.meridian.web.security.RequiredSecrets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Authorisation of card-vault operations beyond the generic {@code cards:write} (SEC-11). Applies in JWT mode
 * (dev mode has no tokens):
 *
 * <ul>
 *   <li>clear PAN / CVV reveal: the exact scope {@value #SCOPE_REVEAL} (no {@code cards:*} / {@code meridian:admin}
 *       shortcut) and, for user tokens, a recent step-up authentication</li>
 *   <li>PIN unblock and key rotation: a service token ({@value #SCOPE_INTERNAL}, e.g. card-service orchestrating an
 *       unblock it authorised itself) or the exact scope {@value #SCOPE_ADMIN} plus recent step-up</li>
 *   <li>lookup (PAN to card), verify (CVV / expiry / PIN oracle) and PIN set: service tokens, or
 *       {@value #SCOPE_ADMIN} plus step-up</li>
 * </ul>
 *
 * Step-up = the token's {@code auth_time} is at most {@code meridian.cards.vault.step-up-max-age-seconds} old and its
 * {@code acr} is one of {@code step-up-acr-values} or its {@code amr} contains one of {@code step-up-amr-values}.
 * A missing step-up is answered with 401 {@code insufficient_user_authentication} (RFC 9470) so the client can
 * re-authenticate with a higher level and retry.
 */
@Component
public class VaultAccessPolicy {
    public static final String SCOPE_REVEAL = "cards:pan_reveal";
    public static final String SCOPE_ADMIN = "cards:vault_admin";
    public static final String SCOPE_INTERNAL = "meridian:internal";

    /** Vault operations with an access rule. */
    public enum Operation { REVEAL, LOOKUP, VERIFY, PIN_SET, PIN_UNBLOCK, ROTATE_KEYS }

    private final boolean devMode;
    private final long maxAgeSeconds;
    private final Set<String> acrValues;
    private final Set<String> amrValues;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public VaultAccessPolicy(Environment env) {
        this(RequiredSecrets.devMode(env),
                env.getProperty("meridian.cards.vault.step-up-max-age-seconds", Long.class, 300L),
                list(env, "meridian.cards.vault.step-up-acr-values", List.of("2", "3", "mfa", "aal2", "aal3")),
                list(env, "meridian.cards.vault.step-up-amr-values", List.of("mfa", "otp", "totp", "hwk", "webauthn")),
                Clock.systemUTC());
    }

    VaultAccessPolicy(boolean devMode, long maxAgeSeconds, Collection<String> acrValues, Collection<String> amrValues,
                      Clock clock) {
        this.devMode = devMode;
        this.maxAgeSeconds = maxAgeSeconds;
        this.acrValues = Set.copyOf(acrValues);
        this.amrValues = Set.copyOf(amrValues);
        this.clock = clock;
    }

    /** Throws 403 / 401 {@link ApiException} unless the current token may perform the operation. */
    public void require(Operation op) {
        if (devMode) {
            return;
        }
        Jwt jwt = currentJwt();
        Set<String> scopes = scopes(jwt);
        boolean service = scopes.contains(SCOPE_INTERNAL);
        switch (op) {
            case REVEAL -> {
                if (!scopes.contains(SCOPE_REVEAL)) {
                    throw new ApiException(403, "insufficient_scope",
                            "Revealing card data requires the dedicated scope " + SCOPE_REVEAL);
                }
                if (!service) {
                    requireStepUp(jwt);
                }
            }
            case PIN_UNBLOCK, ROTATE_KEYS, LOOKUP, VERIFY, PIN_SET -> {
                if (service) {
                    return;
                }
                if (!scopes.contains(SCOPE_ADMIN)) {
                    throw new ApiException(403, "insufficient_scope", "This card-vault operation is reserved for "
                            + "card services or holders of the dedicated scope " + SCOPE_ADMIN);
                }
                requireStepUp(jwt);
            }
            default -> throw new IllegalStateException("unhandled vault operation " + op);
        }
    }

    void requireStepUp(Jwt jwt) {
        Instant authTime = jwt == null ? null : instant(jwt.getClaims().get("auth_time"));
        if (authTime == null || authTime.isBefore(clock.instant().minusSeconds(maxAgeSeconds))) {
            throw new ApiException(401, "insufficient_user_authentication",
                    "Re-authenticate with multi-factor authentication (within the last " + maxAgeSeconds
                            + " seconds) to perform this card-vault operation");
        }
        Object acr = jwt.getClaims().get("acr");
        boolean strongAcr = acr != null && acrValues.contains(String.valueOf(acr));
        boolean strongAmr = false;
        if (jwt.getClaims().get("amr") instanceof Collection<?> amr) {
            for (Object m : amr) {
                strongAmr |= amrValues.contains(String.valueOf(m));
            }
        }
        if (!strongAcr && !strongAmr) {
            throw new ApiException(401, "insufficient_user_authentication",
                    "This card-vault operation requires a multi-factor (step-up) authentication");
        }
    }

    private static Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }

    static Set<String> scopes(Jwt jwt) {
        Set<String> out = new HashSet<>();
        if (jwt == null) {
            return out;
        }
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof String s) {
            out.addAll(List.of(s.trim().split("\\s+")));
        } else if (scope instanceof Collection<?> c) {
            c.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    private static Instant instant(Object v) {
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        if (v instanceof String s && s.matches("\\d+")) {
            return Instant.ofEpochSecond(Long.parseLong(s));
        }
        return null;
    }

    private static List<String> list(Environment env, String name, List<String> fallback) {
        if (env instanceof ConfigurableEnvironment ce) {
            return Binder.get(ce).bind(name, Bindable.listOf(String.class)).orElse(fallback);
        }
        return fallback;
    }
}
