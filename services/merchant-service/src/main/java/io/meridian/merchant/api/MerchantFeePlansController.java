package io.meridian.merchant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.merchant.api.generated.MerchantFeePlansApi;
import io.meridian.merchant.api.generated.model.MerchantFeePlan;
import io.meridian.merchant.api.generated.model.MerchantFeePlanCreate;
import io.meridian.merchant.api.generated.model.MerchantFeePlanUpdate;
import io.meridian.merchant.domain.MdrCalculator;
import io.meridian.merchant.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Merchant discount rate fee plans (validated before they are stored). */
@RestController
public class MerchantFeePlansController implements MerchantFeePlansApi {
    private final CrudEngine crud;
    private final Actions actions;

    public MerchantFeePlansController(CrudEngine crud, Actions actions) {
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<MerchantFeePlan> merchantFeePlansCreate(String idempotencyKey, MerchantFeePlanCreate body) {
        TenantContext.require();
        MdrCalculator.validate(plan(actions.tree(body)));
        return crud.create("merchant_fee_plan", MerchantFeePlan.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<MerchantFeePlan> merchantFeePlansUpdate(UUID id, String ifMatch, MerchantFeePlanUpdate body) {
        TenantContext.require();
        ObjectNode merged = (ObjectNode) actions.tree(crud.get("merchant_fee_plan", MerchantFeePlan.class,
                Crud.params().with("id", id)).getBody());
        actions.tree(body).properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        MdrCalculator.validate(plan(merged));
        return crud.update("merchant_fee_plan", MerchantFeePlan.class, body, ifMatch, Crud.params().with("id", id));
    }

    private static MdrCalculator.Plan plan(JsonNode p) {
        return new MdrCalculator.Plan(Rows.dec(p, "mdr_percentage"), Rows.dec(p, "fixed_fee"), Rows.dec(p, "min_fee"),
                Rows.dec(p, "max_fee"), Rows.dec(p, "refund_fee"), Rows.dec(p, "settlement_fee"));
    }
}
