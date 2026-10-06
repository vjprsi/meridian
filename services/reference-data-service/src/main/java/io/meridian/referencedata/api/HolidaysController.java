package io.meridian.referencedata.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.referencedata.api.generated.HolidaysApi;
import io.meridian.referencedata.api.generated.model.Holiday;
import io.meridian.referencedata.api.generated.model.HolidayCreate;
import io.meridian.referencedata.api.generated.model.HolidayUpdate;
import io.meridian.referencedata.application.CalendarService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Holidays: every change publishes platform.holiday_calendar.changed for the owning calendar. */
@RestController
public class HolidaysController implements HolidaysApi {
    private final CalendarService calendars;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public HolidaysController(CalendarService calendars, CrudEngine crud, Idempotency idempotency) {
        this.calendars = calendars;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<Holiday> holidaysCreate(String idempotencyKey, HolidayCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Holiday.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(crud.read(calendars.createHoliday(body), Holiday.class)));
    }

    @Override
    public ResponseEntity<Holiday> holidaysUpdate(UUID id, String ifMatch, HolidayUpdate body) {
        TenantContext.require();
        return calendars.updateHoliday(id, ifMatch, body, Holiday.class);
    }

    @Override
    public ResponseEntity<Void> holidaysDelete(UUID id) {
        TenantContext.require();
        return calendars.deleteHoliday(id);
    }
}
