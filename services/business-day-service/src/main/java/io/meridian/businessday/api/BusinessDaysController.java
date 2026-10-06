package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.BusinessDaysApi;
import io.meridian.businessday.api.generated.model.BusinessDay;
import io.meridian.businessday.api.generated.model.BusinessDayCreate;
import io.meridian.businessday.api.generated.model.BusinessDaysCutOffRequest;
import io.meridian.businessday.api.generated.model.BusinessDaysReopenRequest;
import io.meridian.businessday.api.generated.model.BusinessDaysRollRequest;
import io.meridian.businessday.api.generated.model.BusinessDaysStartEodRequest;
import io.meridian.businessday.api.generated.model.DateRoll;
import io.meridian.businessday.api.generated.model.EodRun;
import io.meridian.businessday.application.BusinessDayService;
import io.meridian.businessday.application.EodRunService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Business dates: open (create), current, cut-off, start EOD, roll, reopen.
 * Contract: generated/openapi/services/business-day-service.openapi.yaml
 */
@RestController
public class BusinessDaysController implements BusinessDaysApi {
    private final BusinessDayService days;
    private final EodRunService eod;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public BusinessDaysController(BusinessDayService days, EodRunService eod, CrudEngine crud, Idempotency idempotency,
                                  JsonMapper json) {
        this.days = days;
        this.eod = eod;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    /** Opens the first business date of a legal entity; later dates are opened by the roll. */
    @Override
    public ResponseEntity<BusinessDay> businessDaysCreate(String idempotencyKey, BusinessDayCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, BusinessDay.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(crud.read(days.open(req), BusinessDay.class)));
    }

    @Override
    public ResponseEntity<BusinessDay> businessDaysCurrent(UUID legalEntityId) {
        TenantContext.require();
        return ResponseEntity.ok(crud.read(days.current(legalEntityId, false), BusinessDay.class));
    }

    @Override
    public ResponseEntity<BusinessDay> businessDaysCutOff(UUID id, String idempotencyKey, BusinessDaysCutOffRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, BusinessDay.class,
                () -> ResponseEntity.ok(crud.read(days.cutOff(id, Http.text(req, "reason")), BusinessDay.class)));
    }

    @Override
    public ResponseEntity<EodRun> businessDaysStartEod(UUID id, String idempotencyKey, BusinessDaysStartEodRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, EodRun.class,
                () -> ResponseEntity.ok(crud.read(eod.start(id, Http.text(req, "cycle_type")), EodRun.class)));
    }

    @Override
    public ResponseEntity<DateRoll> businessDaysRoll(UUID id, String idempotencyKey, BusinessDaysRollRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        boolean forced = req.path("forced").asBoolean(false);
        return idempotency.execute(idempotencyKey, req, DateRoll.class,
                () -> ResponseEntity.ok(crud.read(days.roll(id, forced, Http.text(req, "reason")), DateRoll.class)));
    }

    @Override
    public ResponseEntity<BusinessDay> businessDaysReopen(UUID id, String idempotencyKey, BusinessDaysReopenRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, BusinessDay.class,
                () -> ResponseEntity.ok(crud.read(days.reopen(id, reason), BusinessDay.class)));
    }
}
