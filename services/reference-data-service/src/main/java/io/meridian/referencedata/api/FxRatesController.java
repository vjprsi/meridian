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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** FX rates: validated publication, bulk load, latest lookup (?base=&quote=&type=) and conversion. */
@RestController
public class FxRatesController implements FxRatesApi {
    private final FxService fx;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final HttpServletRequest request;

    public FxRatesController(FxService fx, CrudEngine crud, Idempotency idempotency, JsonMapper json,
                             HttpServletRequest request) {
        this.fx = fx;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
        this.request = request;
    }

    @Override
    public ResponseEntity<FxRate> fxRatesCreate(String idempotencyKey, FxRateCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, FxRate.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(crud.read(fx.create(body), FxRate.class)));
    }

    @Override
    public ResponseEntity<Void> fxRatesBulk(String idempotencyKey, FxRatesBulkRequest body) {
        TenantContext.require();
        JsonNode rates = json.valueToTree(body).get("rates");
        return Responses.body(idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(fx.bulk(rates))));
    }

    @Override
    public ResponseEntity<Void> fxRatesConvert(String idempotencyKey, FxRatesConvertRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return Responses.body(idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.ok(fx.convert(req))));
    }

    @Override
    public ResponseEntity<FxRate> fxRatesLatest() {
        TenantContext.require();
        JsonNode rate = fx.latest(request.getParameter("base"), request.getParameter("quote"), request.getParameter("type"));
        return ResponseEntity.ok(json.treeToValue(rate, FxRate.class));
    }
}
