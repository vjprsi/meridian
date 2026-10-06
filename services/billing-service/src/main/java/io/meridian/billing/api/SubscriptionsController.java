package io.meridian.billing.api;

import io.meridian.billing.api.generated.SubscriptionsApi;
import io.meridian.billing.api.generated.model.SubscriptionsCancelRequest;
import io.meridian.billing.api.generated.model.SubscriptionsChangePlanRequest;
import io.meridian.billing.api.generated.model.TenantSubscription;
import io.meridian.billing.api.generated.model.TenantSubscriptionCreate;
import io.meridian.billing.api.generated.model.TenantSubscriptionUpdate;
import io.meridian.billing.application.SubscriptionService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Tenant subscriptions: create, PATCH terms/status, prorated plan change, cancel at period end. */
@RestController
public class SubscriptionsController implements SubscriptionsApi {
    private final SubscriptionService subscriptions;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public SubscriptionsController(SubscriptionService subscriptions, CrudEngine crud, Idempotency idempotency) {
        this.subscriptions = subscriptions;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<TenantSubscription> subscriptionsCreate(String idempotencyKey, TenantSubscriptionCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, TenantSubscription.class, () -> {
            TenantSubscription s = crud.read(subscriptions.create(body), TenantSubscription.class);
            return Responses.withEtag(HttpStatus.CREATED, s, s.getRowVersion());
        });
    }

    @Override
    public ResponseEntity<TenantSubscription> subscriptionsUpdate(UUID id, String ifMatch, TenantSubscriptionUpdate body) {
        TenantContext.require();
        TenantSubscription s = crud.read(subscriptions.update(id, Responses.version(ifMatch), body), TenantSubscription.class);
        return Responses.withEtag(HttpStatus.OK, s, s.getRowVersion());
    }

    @Override
    public ResponseEntity<TenantSubscription> subscriptionsChangePlan(UUID id, String idempotencyKey,
                                                                      SubscriptionsChangePlanRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "change-plan", "id", id.toString(), "body", body),
                TenantSubscription.class, () -> ResponseEntity.ok(crud.read(
                        subscriptions.changePlan(id, body.getPricePlanId(), body.getEffectiveDate()),
                        TenantSubscription.class)));
    }

    @Override
    public ResponseEntity<TenantSubscription> subscriptionsCancel(UUID id, String idempotencyKey,
                                                                  SubscriptionsCancelRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "cancel", "id", id.toString(), "body", body),
                TenantSubscription.class, () -> ResponseEntity.ok(crud.read(
                        subscriptions.cancel(id, body.getReason(), LocalDate.now(ZoneOffset.UTC)), TenantSubscription.class)));
    }
}
