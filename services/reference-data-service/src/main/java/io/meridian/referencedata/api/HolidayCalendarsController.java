package io.meridian.referencedata.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.referencedata.api.generated.HolidayCalendarsApi;
import io.meridian.referencedata.api.generated.model.HolidayCalendar;
import io.meridian.referencedata.api.generated.model.HolidayCalendarCreate;
import io.meridian.referencedata.api.generated.model.HolidayCalendarUpdate;
import io.meridian.referencedata.api.generated.model.HolidayCalendarsImportRequest;
import io.meridian.referencedata.application.CalendarService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Holiday calendars: maintenance, business-day queries and holiday import. */
@RestController
public class HolidayCalendarsController implements HolidayCalendarsApi {
    private final CalendarService calendars;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public HolidayCalendarsController(CalendarService calendars, CrudEngine crud, Idempotency idempotency,
                                      JsonMapper json) {
        this.calendars = calendars;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<HolidayCalendar> holidayCalendarsCreate(String idempotencyKey, HolidayCalendarCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, HolidayCalendar.class, () -> ResponseEntity
                .status(HttpStatus.CREATED).body(crud.read(calendars.createCalendar(body), HolidayCalendar.class)));
    }

    @Override
    public ResponseEntity<HolidayCalendar> holidayCalendarsUpdate(UUID id, String ifMatch, HolidayCalendarUpdate body) {
        TenantContext.require();
        return calendars.updateCalendar(id, ifMatch, body, HolidayCalendar.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> holidayCalendarsIsBusinessDay(UUID id, LocalDate date) {
        TenantContext.require();
        return Responses.ok(json, calendars.isBusinessDay(id, date));
    }

    @Override
    public ResponseEntity<Map<String, Object>> holidayCalendarsNextBusinessDay(UUID id, LocalDate date, Integer days) {
        TenantContext.require();
        return Responses.ok(json, calendars.nextBusinessDay(id, date, days == null ? 1 : days));
    }

    @Override
    public ResponseEntity<Map<String, Object>> holidayCalendarsImport(UUID id, String idempotencyKey,
                                                                      HolidayCalendarsImportRequest body) {
        TenantContext.require();
        return Responses.object(json, idempotency.execute(idempotencyKey, body, JsonNode.class, () -> ResponseEntity.ok(
                calendars.importHolidays(id, body.getYear(), body.getSource()))));
    }
}
