package io.meridian.routing.config;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * routing-service settings ({@code meridian.routing.*}).
 *
 * @param ledgerId          ledger book used for settlement and liquidity postings
 * @param callbackSecret    default HMAC secret for inbound rail callbacks (X-Rail-Signature)
 * @param callbackSecrets   per-rail-code override of the callback secret
 * @param degradedThreshold success rate below which a rail is marked DEGRADED by the metrics feed
 * @param testTimeoutMs     timeout of adapter connection tests
 * @param gl                internal ledger accounts
 */
@ConfigurationProperties(prefix = "meridian.routing")
public record RoutingProperties(UUID ledgerId, String callbackSecret, Map<String, String> callbackSecrets,
                                BigDecimal degradedThreshold, Integer testTimeoutMs, Gl gl) {
    public RoutingProperties {
        callbackSecrets = callbackSecrets == null ? Map.of() : Map.copyOf(callbackSecrets);
        degradedThreshold = degradedThreshold == null ? new BigDecimal("0.95") : degradedThreshold;
        testTimeoutMs = testTimeoutMs == null ? 3000 : testTimeoutMs;
        gl = gl == null ? new Gl(null) : gl;
    }

    /** @param treasuryFunding treasury account debited... credited when a nostro is topped up */
    public record Gl(UUID treasuryFunding) {}

    public String secretFor(String railCode) {
        String s = railCode == null ? null : callbackSecrets.get(railCode);
        return s != null ? s : callbackSecret;
    }
}
