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
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Holiday calendars: maintenance, business-day queries and holiday import. The contract omits the query
 * parameters of the GET actions (?date=, ?days=), so they are read from the request.
 */
@RestController
public class HolidayCalendarsController implements HolidayCalendarsApi {
    private final CalendarService calendars;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final HttpServletRequest request;

    public HolidayCalendarsController(CalendarService calendars, CrudEngine crud, Idempotency idempotency,
                                      HttpServletRequest request) {
        this.calendars = calendars;
        this.crud = crud;
        this.idempotency = idempotency;
        this.request = request;
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
    public ResponseEntity<Void> holidayCalendarsIsBusinessDay(UUID id) {
        TenantContext.require();
        return Responses.ok(calendars.isBusinessDay(id, Responses.date(request.getParameter("date"), "date")));
    }

    @Override
    public ResponseEntity<Void> holidayCalendarsNextBusinessDay(UUID id) {
        TenantContext.require();
        return Responses.ok(calendars.nextBusinessDay(id, Responses.date(request.getParameter("date"), "date"),
                Responses.integer(request.getParameter("days"), "days", 1)));
    }

    @Override
    public ResponseEntity<Void> holidayCalendarsImport(UUID id, String idempotencyKey, HolidayCalendarsImportRequest body) {
        TenantContext.require();
        return Responses.body(idempotency.execute(idempotencyKey, body, JsonNode.class, () -> ResponseEntity.ok(
                calendars.importHolidays(id, body.getYear(), body.getSource()))));
    }
}
