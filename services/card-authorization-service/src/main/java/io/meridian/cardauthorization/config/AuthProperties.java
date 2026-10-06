package io.meridian.cardauthorization.config;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * card-authorization-service settings ({@code meridian.cards.auth.*}).
 *
 * @param jitSecret       HMAC-SHA256 key signing JIT funding webhooks (X-Signature)
 * @param referenceRates  fallback conversion rates when the network sends no DE10, key {@code <FROM>_<TO>}
 * @param ruleCacheTtlMs  how long compiled authorisation rules are cached per tenant (rule CRUD invalidates)
 * @param defaultHoldDays hold expiry when the program does not say
 * @param defaultFraudTimeoutMs fraud scoring budget when the program does not say
 * @param defaultScheme   scheme used by sandbox clearing simulation when the program does not say
 */
@ConfigurationProperties(prefix = "meridian.cards.auth")
public record AuthProperties(String jitSecret, Map<String, BigDecimal> referenceRates, Long ruleCacheTtlMs,
                             Integer defaultHoldDays, Integer defaultFraudTimeoutMs, String defaultScheme) {

    public AuthProperties {
        jitSecret = jitSecret == null ? "dev-jit-secret" : jitSecret;
        referenceRates = referenceRates == null ? Map.of() : Map.copyOf(referenceRates);
        ruleCacheTtlMs = ruleCacheTtlMs == null ? 5_000L : ruleCacheTtlMs;
        defaultHoldDays = defaultHoldDays == null ? 7 : defaultHoldDays;
        defaultFraudTimeoutMs = defaultFraudTimeoutMs == null ? 80 : defaultFraudTimeoutMs;
        defaultScheme = defaultScheme == null ? "VISA" : defaultScheme;
    }

    /** Reference rate FROM -> TO (direct, or the inverse of TO -> FROM); null when unknown. */
    public BigDecimal rate(String from, String to) {
        BigDecimal direct = referenceRates.get(from + "_" + to);
        if (direct != null) {
            return direct;
        }
        BigDecimal inverse = referenceRates.get(to + "_" + from);
        return inverse == null || inverse.signum() == 0 ? null
                : BigDecimal.ONE.divide(inverse, java.math.MathContext.DECIMAL64);
    }
}
