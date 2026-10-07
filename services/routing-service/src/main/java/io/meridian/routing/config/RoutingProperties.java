package io.meridian.routing.config;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * routing-service settings ({@code meridian.routing.*}).
 *
 * <p>The ledger book and internal accounts are tenant data: resolved per tenant from config-service (namespace
 * {@code ledger}, key {@code routing-service}: {@code ledger_id}, {@code gl.treasury_funding}), falling back to the
 * deployment values here.
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

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return io.meridian.web.config.TenantSettings.uuid("routing-service", "ledger_id", ledgerId);
    }

    /** Internal accounts, per tenant from config-service (namespace {@code ledger}, key {@code routing-service}). */
    @Override
    public Gl gl() {
        return new Gl(io.meridian.web.config.TenantSettings.uuid("routing-service", "gl.treasury_funding", gl.treasuryFunding()));
    }

    public String secretFor(String railCode) {
        String s = railCode == null ? null : callbackSecrets.get(railCode);
        return s != null ? s : callbackSecret;
    }
}
