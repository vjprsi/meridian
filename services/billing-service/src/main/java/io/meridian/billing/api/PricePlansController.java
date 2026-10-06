package io.meridian.billing.api;

import io.meridian.billing.api.generated.PricePlansApi;
import io.meridian.billing.api.generated.model.PricePlan;
import io.meridian.billing.api.generated.model.PricePlanCreate;
import io.meridian.billing.api.generated.model.PricePlanUpdate;
import io.meridian.billing.api.generated.model.PricePlansRetireRequest;
import io.meridian.billing.application.PricePlanService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Price plans: validated create and PATCH; lifecycle DRAFT -> ACTIVE (/publish) -> RETIRED (/retire). */
@RestController
public class PricePlansController implements PricePlansApi {
    private final PricePlanService plans;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public PricePlansController(PricePlanService plans, CrudEngine crud, Idempotency idempotency) {
        this.plans = plans;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<PricePlan> pricePlansCreate(String idempotencyKey, PricePlanCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, PricePlan.class, () -> {
            PricePlan p = crud.read(plans.create(body), PricePlan.class);
            return Responses.withEtag(HttpStatus.CREATED, p, p.getRowVersion());
        });
    }

    @Override
    public ResponseEntity<PricePlan> pricePlansUpdate(UUID id, String ifMatch, PricePlanUpdate body) {
        TenantContext.require();
        PricePlan p = crud.read(plans.update(id, Responses.version(ifMatch), body), PricePlan.class);
        return Responses.withEtag(HttpStatus.OK, p, p.getRowVersion());
    }

    @Override
    public ResponseEntity<PricePlan> pricePlansPublish(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "publish", "id", id.toString()), PricePlan.class,
                () -> {
                    PricePlan p = crud.read(plans.publish(id), PricePlan.class);
                    return Responses.withEtag(HttpStatus.OK, p, p.getRowVersion());
                });
    }

    @Override
    public ResponseEntity<PricePlan> pricePlansRetire(UUID id, String idempotencyKey, PricePlansRetireRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "retire", "id", id.toString(), "body", body),
                PricePlan.class, () -> {
                    PricePlan p = crud.read(plans.retire(id, body.getReason()), PricePlan.class);
                    return Responses.withEtag(HttpStatus.OK, p, p.getRowVersion());
                });
    }
}
