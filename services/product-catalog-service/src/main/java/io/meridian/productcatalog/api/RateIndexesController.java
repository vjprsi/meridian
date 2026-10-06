package io.meridian.productcatalog.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.RateIndexesApi;
import io.meridian.productcatalog.api.generated.model.RateIndexValue;
import io.meridian.productcatalog.api.generated.model.RateIndexValuePage;
import io.meridian.productcatalog.api.generated.model.RateIndexesValuesRequest;
import io.meridian.productcatalog.application.RateIndexService;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rate indexes: append-only published fixings. */
@RestController
public class RateIndexesController implements RateIndexesApi {
    private final RateIndexService indexes;
    private final CatalogStore store;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public RateIndexesController(RateIndexService indexes, CatalogStore store, CrudEngine crud, Idempotency idempotency,
                                 JsonMapper json) {
        this.indexes = indexes;
        this.store = store;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<RateIndexValue> rateIndexesAddValues(UUID id, String idempotencyKey, RateIndexesValuesRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        LocalDate date = LocalDate.parse(req.get("value_date").asString());
        BigDecimal rate = new BigDecimal(req.get("rate").asString());
        return idempotency.execute(idempotencyKey, ApiSupport.idem(json, "index-value", id, body), RateIndexValue.class,
                () -> ResponseEntity.ok(crud.read(indexes.publishValue(id, date, rate, ApiSupport.text(req, "source_ref")),
                        RateIndexValue.class)));
    }

    /** {@code GET /v1/rate-indexes/{id}/values?from=&to=} newest first. */
    @Override
    public ResponseEntity<RateIndexValuePage> rateIndexesValues(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("rate_index", id);
        LocalDate from = date("from");
        LocalDate to = date("to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("invalid_range", "to is before from");
        }
        return ResponseEntity.ok(json.convertValue(store.indexValues(id, from, to, limit, cursor), RateIndexValuePage.class));
    }

    private static LocalDate date(String name) {
        String v = ApiSupport.param(name);
        try {
            return v == null ? null : LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_date", name + " must be an ISO date");
        }
    }
}
