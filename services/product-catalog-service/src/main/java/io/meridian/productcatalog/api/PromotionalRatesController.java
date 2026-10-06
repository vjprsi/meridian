package io.meridian.productcatalog.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.PromotionalRatesApi;
import io.meridian.productcatalog.api.generated.model.PromotionalRate;
import io.meridian.productcatalog.api.generated.model.PromotionalRateCreate;
import io.meridian.productcatalog.api.generated.model.PromotionalRateUpdate;
import io.meridian.productcatalog.api.generated.model.PromotionalRatesCancelRequest;
import io.meridian.productcatalog.application.PromotionService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Promotional rates: validated creation (status follows the window), guarded edits, cancellation. */
@RestController
public class PromotionalRatesController implements PromotionalRatesApi {
    private final PromotionService promotions;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public PromotionalRatesController(PromotionService promotions, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.promotions = promotions;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<PromotionalRate> promotionalRatesCreate(String idempotencyKey, PromotionalRateCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, PromotionalRate.class,
                () -> respond(HttpStatus.CREATED, promotions.create(body)));
    }

    @Override
    public ResponseEntity<PromotionalRate> promotionalRatesUpdate(UUID id, String ifMatch, PromotionalRateUpdate body) {
        TenantContext.require();
        return respond(HttpStatus.OK, promotions.update(id, body, ApiSupport.ifMatch(ifMatch)));
    }

    @Override
    public ResponseEntity<PromotionalRate> promotionalRatesCancel(UUID id, String idempotencyKey, PromotionalRatesCancelRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, ApiSupport.idem(json, "cancel", id, body), PromotionalRate.class,
                () -> respond(HttpStatus.OK, promotions.cancel(id, ApiSupport.text(req, "reason"))));
    }

    private ResponseEntity<PromotionalRate> respond(HttpStatus status, String row) {
        return ApiSupport.withEtag(status, crud.read(row, PromotionalRate.class), json.readTree(row));
    }
}
