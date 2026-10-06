package io.meridian.kyc.api;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.KycChecksApi;
import io.meridian.kyc.api.generated.model.KycCheck;
import io.meridian.kyc.api.generated.model.KycCheckCreate;
import io.meridian.kyc.api.generated.model.KycChecksProviderCallbacksEventsRequest;
import io.meridian.kyc.api.generated.model.KycChecksRetryRequest;
import io.meridian.kyc.application.CheckService;
import io.meridian.kyc.application.KycSupport;
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

    @Override
    public ResponseEntity<Void> kycChecksProviderCallbacksEvents(String providerCode, String idempotencyKey,
                                                                 KycChecksProviderCallbacksEventsRequest body) {
        JsonNode payload = kyc.tree(body).get("payload");
        if (payload == null || !payload.isObject()) {
            throw ApiException.badRequest("invalid_callback", "payload object is required");
        }
        return actions.raw(idempotencyKey, "check.callback", providerCode, body, Void.class, () -> {
            checks.callback(providerCode, payload);
            return ResponseEntity.noContent().build();
        });
    }
}
