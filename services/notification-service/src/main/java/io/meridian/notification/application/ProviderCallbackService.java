package io.meridian.notification.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.domain.CallbackSignature;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Public provider callbacks (no bearer token, no Idempotency-Key). The tenant comes from the {@code tenant_id} of the
 * callback URL registered with the provider; the request must carry {@link CallbackSignature#HEADER}, an HMAC of the
 * payload keyed with the provider's callback secret {@code meridian.notifications.callback-secrets.<provider>}.
 * Providers without a configured secret are rejected. Receipts are naturally idempotent (the status only moves
 * forward), so replays are harmless.
 */
@Service
public class ProviderCallbackService {
    static final String SECRET_PREFIX = "meridian.notifications.callback-secrets.";

    private final NotificationService notifications;
    private final Environment env;
    private final JsonMapper json;

    private final io.meridian.web.callback.CallbackVerifier callbacks;

    public ProviderCallbackService(NotificationService notifications, Environment env, JsonMapper json,
                                   io.meridian.web.callback.CallbackVerifier callbacks) {
        this.callbacks = callbacks;
        this.notifications = notifications;
        this.env = env;
        this.json = json;
    }

    public Map<String, Object> handle(String provider, UUID tenantId, Map<String, Object> payload, String signature) {
        String secret = env.getProperty(SECRET_PREFIX + provider.toLowerCase(Locale.ROOT));
        if (secret == null || secret.isBlank() || tenantId == null) {
            throw new ApiException(401, "invalid_signature",
                    "Missing or invalid " + CallbackSignature.HEADER + " for provider " + provider);
        }
        // SEC-17: v2 over "<ts>.<nonce>.<tenant_id>.<canonical payload>" (fresh, single use, tenant-bound)
        callbacks.verify("notify:" + provider.toLowerCase(Locale.ROOT), tenantId.toString(),
                CallbackSignature.canonical(json.valueToTree(payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                signature, io.meridian.web.callback.CallbackVerifier.Headers.current(),
                b -> CallbackSignature.signText(secret, new String(b, java.nio.charset.StandardCharsets.UTF_8)));
        try {
            return TenantContext.callAs(tenantId, "provider-callback:" + provider,
                    () -> notifications.providerCallback(provider, payload));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
