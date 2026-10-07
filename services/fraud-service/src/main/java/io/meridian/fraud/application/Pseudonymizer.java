package io.meridian.fraud.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.domain.Pseudonyms;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tenant-scoped {@link Pseudonyms} of fraud-service. The secret {@code meridian.fraud.pseudonym-key}
 * (env {@code MERIDIAN_FRAUD_PSEUDONYM_KEY}, Helm secret {@code meridian-fraud-service-secrets/pseudonym-key}) is
 * declared in {@code meridian.required-secrets}: outside {@code security-mode=dev} the service refuses to start when it
 * is missing, short or the committed development default ({@code RequiredSecrets}, SEC-02). Rotating it changes every
 * pseudonym: velocity history restarts and fraud list entries must be re-added.
 */
@Component
public class Pseudonymizer {
    /** Development default, accepted only in security-mode=dev (RequiredSecrets refuses it elsewhere). */
    static final String DEV_KEY = "dev-only-fraud-pseudonym-key-not-for-production";

    private final byte[] secret;
    private final Map<UUID, Pseudonyms> tenants = new ConcurrentHashMap<>();

    public Pseudonymizer(@Value("${meridian.fraud.pseudonym-key:" + DEV_KEY + "}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("meridian.fraud.pseudonym-key is not set");
        }
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Pseudonyms of the bound tenant. */
    public Pseudonyms current() {
        return forTenant(TenantContext.require());
    }

    public Pseudonyms forTenant(UUID tenant) {
        if (tenants.size() > 10_000) {
            tenants.clear();
        }
        return tenants.computeIfAbsent(tenant, t -> Pseudonyms.of(Pseudonyms.tenantKey(secret, t)));
    }
}
