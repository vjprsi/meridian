package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.BusinessDaysApi;
import io.meridian.businessday.api.generated.model.BusinessDay;
import io.meridian.businessday.api.generated.model.BusinessDaysCutOffRequest;
import io.meridian.businessday.api.generated.model.BusinessDaysReopenRequest;
import io.meridian.businessday.api.generated.model.BusinessDaysRollRequest;
import io.meridian.businessday.api.generated.model.BusinessDaysStartEodRequest;
import io.meridian.businessday.api.generated.model.DateRoll;
import io.meridian.businessday.api.generated.model.EodRun;
import io.meridian.businessday.application.BusinessDayService;
import io.meridian.businessday.application.EodRunService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Business dates: open (extension, see below), current, cut-off, start EOD, roll, reopen.
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

    /**
     * Opens the first business date of a legal entity. Not in the generated contract (business-days has no
     * create operation), but without it no date could ever exist; later dates are opened by the roll.
     * Body: {@code legal_entity_id}, {@code business_date}, optional {@code holiday_calendar_id}, {@code notes}.
     */
    @PostMapping(value = "/v1/operations/business-days", consumes = "application/json", produces = "application/json")
    public ResponseEntity<BusinessDay> businessDaysOpen(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, BusinessDay.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(crud.read(days.open(body), BusinessDay.class)));
    }

    @Override
    public ResponseEntity<BusinessDay> businessDaysCurrent() {
        TenantContext.require();
        String entity = Http.query("legal_entity_id");
        UUID id;
        try {
            id = entity == null ? null : UUID.fromString(entity);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_value", "legal_entity_id must be a UUID");
        }
        return ResponseEntity.ok(crud.read(days.current(id, false), BusinessDay.class));
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
