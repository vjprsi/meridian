package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.RedemptionsApi;
import io.meridian.rewards.api.generated.model.Redemption;
import io.meridian.rewards.api.generated.model.RedemptionCreate;
import io.meridian.rewards.api.generated.model.RedemptionsCancelRequest;
import io.meridian.rewards.api.generated.model.RedemptionsFailRequest;
import io.meridian.rewards.api.generated.model.RedemptionsFulfilRequest;
import io.meridian.rewards.application.Json;
import io.meridian.rewards.application.RedemptionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Points redemptions with fulfilment tracking. */
@RestController
public class RedemptionsController implements RedemptionsApi {
    private final RedemptionService redemptions;
    private final Http http;

    public RedemptionsController(RedemptionService redemptions, Http http) {
        this.redemptions = redemptions;
        this.http = http;
    }

    @Override
    public ResponseEntity<Redemption> redemptionsCreate(String idempotencyKey, RedemptionCreate body) {
        return http.created(idempotencyKey, "redemption.create", body, Redemption.class,
                () -> redemptions.create(body, idempotencyKey));
    }

    @Override
    public ResponseEntity<Redemption> redemptionsCancel(UUID id, String idempotencyKey, RedemptionsCancelRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "redemption.cancel", id, body, Redemption.class, () -> redemptions.cancel(id, reason));
    }

    @Override
    public ResponseEntity<Redemption> redemptionsRetry(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "redemption.retry", id, null, Redemption.class, () -> redemptions.retry(id));
    }

    @Override
    public ResponseEntity<Redemption> redemptionsFulfil(UUID id, String idempotencyKey, RedemptionsFulfilRequest body) {
        String ref = Json.required(http.tree(body), "partner_reference");
        return http.ok(idempotencyKey, "redemption.fulfil", id, body, Redemption.class, () -> redemptions.fulfil(id, ref));
    }

    @Override
    public ResponseEntity<Redemption> redemptionsFail(UUID id, String idempotencyKey, RedemptionsFailRequest body) {
        String reason = Json.required(http.tree(body), "failure_reason");
        return http.ok(idempotencyKey, "redemption.fail", id, body, Redemption.class, () -> redemptions.fail(id, reason));
    }
}
