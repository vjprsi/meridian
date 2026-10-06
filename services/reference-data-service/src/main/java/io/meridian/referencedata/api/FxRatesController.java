package io.meridian.referencedata.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.referencedata.api.generated.FxRatesApi;
import io.meridian.referencedata.api.generated.model.FxRate;
import io.meridian.referencedata.api.generated.model.FxRateCreate;
import io.meridian.referencedata.api.generated.model.FxRatesBulkRequest;
import io.meridian.referencedata.api.generated.model.FxRatesConvertRequest;
import io.meridian.referencedata.application.FxService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** FX rates: validated publication, bulk load, latest lookup and conversion. */
@RestController
public class FxRatesController implements FxRatesApi {
    private final FxService fx;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public FxRatesController(FxService fx, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.fx = fx;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<FxRate> fxRatesCreate(String idempotencyKey, FxRateCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, FxRate.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(crud.read(fx.create(body), FxRate.class)));
    }

    /** Loads the rates all-or-nothing; the optional top-level fields are defaults applied to every rate. */
    @Override
    public ResponseEntity<Map<String, Object>> fxRatesBulk(String idempotencyKey, FxRatesBulkRequest body) {
        TenantContext.require();
        JsonNode rates = json.valueToTree(body.getRates());
        ObjectNode defaults = json.createObjectNode();
        if (body.getFxRateSourceId() != null) {
            defaults.put("fx_rate_source_id", body.getFxRateSourceId().toString());
        }
        if (body.getRateType() != null) {
            defaults.put("rate_type", body.getRateType().getValue());
        }
        if (body.getAsOfDate() != null) {
            defaults.put("as_of_date", body.getAsOfDate().toString());
        }
        if (body.getValidFrom() != null) {
            defaults.put("valid_from", body.getValidFrom().toString());
        }
        if (body.getValidTo() != null) {
            defaults.put("valid_to", body.getValidTo().toString());
        }
        return Responses.object(json, idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(fx.bulk(rates, defaults))));
    }

    @Override
    public ResponseEntity<Map<String, Object>> fxRatesConvert(String idempotencyKey, FxRatesConvertRequest body) {
        TenantContext.require();
        String rateType = body.getRateType() == null ? null : body.getRateType().getValue();
        return Responses.object(json, idempotency.execute(idempotencyKey, body, JsonNode.class, () -> ResponseEntity.ok(
                fx.convert(body.getAmount(), body.getFromCurrency(), body.getToCurrency(), rateType))));
    }

    @Override
    public ResponseEntity<FxRate> fxRatesLatest(String base, String quote, String type) {
        TenantContext.require();
        return ResponseEntity.ok(json.treeToValue(fx.latest(base, quote, type), FxRate.class));
    }
}
