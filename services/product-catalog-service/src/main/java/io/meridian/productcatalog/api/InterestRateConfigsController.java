package io.meridian.productcatalog.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.InterestRateConfigsApi;
import io.meridian.productcatalog.api.generated.model.InterestRateConfig;
import io.meridian.productcatalog.api.generated.model.InterestRateConfigCreate;
import io.meridian.productcatalog.api.generated.model.InterestRateConfigUpdate;
import io.meridian.productcatalog.api.generated.model.InterestRateConfigsEffectiveRateRequest;
import io.meridian.productcatalog.api.generated.model.InterestRateTierPage;
import io.meridian.productcatalog.application.PricingService;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Interest-rate configurations (guarded content), their tiers and the effective-rate calculation. */
@RestController
public class InterestRateConfigsController implements InterestRateConfigsApi {
    private final ContentEndpoints content;
    private final PricingService pricing;
    private final CatalogStore store;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public InterestRateConfigsController(ContentEndpoints content, PricingService pricing, CatalogStore store,
                                         CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.content = content;
        this.pricing = pricing;
        this.store = store;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<InterestRateConfig> interestRateConfigsCreate(String idempotencyKey, InterestRateConfigCreate body) {
        return content.create("interest_rate_config", InterestRateConfig.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<InterestRateConfig> interestRateConfigsUpdate(UUID id, String ifMatch, InterestRateConfigUpdate body) {
        return content.update("interest_rate_config", InterestRateConfig.class, id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Void> interestRateConfigsDelete(UUID id) {
        return content.delete("interest_rate_config", id);
    }

    @Override
    public ResponseEntity<InterestRateTierPage> interestRateConfigsTiers(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("interest_rate_config", id);
        return crud.list("interest_rate_tier", InterestRateTierPage.class,
                Crud.params().with("interest_rate_config_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> interestRateConfigsEffectiveRate(UUID id, String idempotencyKey,
            InterestRateConfigsEffectiveRateRequest body) {
        TenantContext.require();
        return ApiSupport.result(idempotency, json, idempotencyKey, ApiSupport.idem(json, "effective-rate", id, body),
                () -> store.toNode(pricing.effectiveRate(id, body.getBalance(), body.getAsOf())));
    }
}
