package io.meridian.kyc.api;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.KycChecksApi;
import io.meridian.kyc.api.generated.model.KycCheck;
import io.meridian.kyc.api.generated.model.KycCheckCreate;
import io.meridian.kyc.api.generated.model.KycChecksProviderCallbacksEventsRequest;
import io.meridian.kyc.api.generated.model.KycChecksRetryRequest;
import io.meridian.kyc.application.CheckService;
import io.meridian.kyc.application.KycSupport;
import io.meridian.common.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** KYC checks: provider calls, retries, asynchronous provider callbacks. */
@RestController
public class KycChecksController implements KycChecksApi {
    private final CheckService checks;
    private final Actions actions;
    private final KycSupport kyc;

    public KycChecksController(CheckService checks, Actions actions, KycSupport kyc) {
        this.checks = checks;
        this.actions = actions;
        this.kyc = kyc;
    }

    @Override
    public ResponseEntity<KycCheck> kycChecksCreate(String idempotencyKey, KycCheckCreate body) {
        return actions.created(idempotencyKey, "check.create", body, KycCheck.class, () -> checks.create(body));
    }

    @Override
    public ResponseEntity<KycCheck> kycChecksRetry(UUID id, String idempotencyKey, KycChecksRetryRequest body) {
        boolean fallback = KycSupport.bool(kyc.tree(body), "use_fallback");
        return actions.ok(idempotencyKey, "check.retry", id, body, KycCheck.class, () -> checks.retry(id, fallback));
    }

    /**
     * Public provider webhook (no bearer token, no Idempotency-Key). The HMAC signature is verified before anything
     * else; the tenant comes from the authenticated context when present, otherwise from the signed
     * {@code payload.tenant_id} the provider echoes back from the check request. Duplicate deliveries are no-ops.
     */
    @Override
    public ResponseEntity<Void> kycChecksProviderCallbacksEvents(String providerCode,
                                                                 KycChecksProviderCallbacksEventsRequest body) {
        JsonNode payload = kyc.tree(body).get("payload");
        if (payload == null || !payload.isObject()) {
            throw ApiException.badRequest("invalid_callback", "payload object is required");
        }
        checks.verifyCallback(providerCode, payload);
        Optional<UUID> tenant = TenantContext.current().filter(t -> !TenantContext.NO_TENANT.equals(t));
        UUID target = tenant.orElseGet(() -> {
            UUID fromPayload = KycSupport.uuid(payload, "tenant_id");
            if (fromPayload == null) {
                throw ApiException.unprocessable("tenant_unresolved", "payload.tenant_id is required for provider callbacks");
            }
            return fromPayload;
        });
        try {
            TenantContext.callAs(target, "kyc-provider:" + providerCode, () -> {
                checks.callback(providerCode, payload);
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ResponseEntity.noContent().build();
    }
}
