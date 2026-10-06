package io.meridian.referencedata.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.referencedata.domain.BusinessCalendar;
import io.meridian.referencedata.domain.HolidayGenerator;
import io.meridian.referencedata.domain.HolidayGenerator.GeneratedHoliday;
import io.meridian.referencedata.infrastructure.RefDataStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Holiday calendars and holidays: maintenance with change events, business-day arithmetic and holiday import.
 * weekend_days is stored canonically as an array of upper-case weekday names, e.g. ["SATURDAY", "SUNDAY"].
 */
@Service
public class CalendarService {
    private final CrudEngine crud;
    private final RefDataStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public CalendarService(CrudEngine crud, RefDataStore store, OutboxWriter outbox, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    // ------------------------------------------------------------------ calendars

    @Transactional
    public String createCalendar(Object body) {
        ObjectNode v = crud.toColumns("holiday_calendar", body);
        v.set("weekend_days", canonicalWeekend(v.get("weekend_days")));
        validateTimezone(v);
        String row = crud.insert("holiday_calendar", v);
        JsonNode r = json.readTree(row);
        changed(UUID.fromString(r.get("id").asString()), "CALENDAR_CREATED", null);
        return row;
    }

    @Transactional
    public <T> ResponseEntity<T> updateCalendar(UUID id, String ifMatch, Object body, Class<T> type) {
        calendar(id);
        ObjectNode v = crud.toColumns("holiday_calendar", body);
        if (v.has("weekend_days")) {
            v.set("weekend_days", canonicalWeekend(v.get("weekend_days")));
        }
        validateTimezone(v);
        ResponseEntity<T> r = crud.update("holiday_calendar", type, v, ifMatch, Crud.params().with("id", id));
        changed(id, "CALENDAR_UPDATED", null);
        return r;
    }

    // ------------------------------------------------------------------ holidays

    @Transactional
    public String createHoliday(Object body) {
        ObjectNode v = crud.toColumns("holiday", body);
        UUID calendarId = UUID.fromString(v.get("holiday_calendar_id").asString());
        calendarFor422(calendarId);
        String row = crud.insert("holiday", v);
        changed(calendarId, "HOLIDAY_ADDED", json.readTree(row));
        return row;
    }

    @Transactional
    public <T> ResponseEntity<T> updateHoliday(UUID id, String ifMatch, Object body, Class<T> type) {
        JsonNode before = store.byId("holiday", id).orElseThrow(() -> ApiException.notFound("holiday", id));
        ObjectNode v = crud.toColumns("holiday", body);
        if (v.hasNonNull("holiday_calendar_id")) {
            calendarFor422(UUID.fromString(v.get("holiday_calendar_id").asString()));
        }
        ResponseEntity<T> r = crud.update("holiday", type, body, ifMatch, Crud.params().with("id", id));
        JsonNode after = json.valueToTree(r.getBody());
        UUID calendarId = UUID.fromString(after.get("holiday_calendar_id").asString());
        changed(calendarId, "HOLIDAY_UPDATED", after);
        UUID oldCalendar = UUID.fromString(before.get("holiday_calendar_id").asString());
        if (!oldCalendar.equals(calendarId)) {
            changed(oldCalendar, "HOLIDAY_REMOVED", before);
        }
        return r;
    }

    @Transactional
    public ResponseEntity<Void> deleteHoliday(UUID id) {
        JsonNode before = store.byId("holiday", id).orElseThrow(() -> ApiException.notFound("holiday", id));
        ResponseEntity<Void> r = crud.delete("holiday", Crud.params().with("id", id));
        changed(UUID.fromString(before.get("holiday_calendar_id").asString()), "HOLIDAY_REMOVED", before);
        return r;
    }

    // ------------------------------------------------------------------ business days

    @Transactional(readOnly = true)
    public JsonNode isBusinessDay(UUID calendarId, LocalDate date) {
        JsonNode cal = calendar(calendarId);
        BusinessCalendar bc = load(cal, date.minusDays(1), date.plusDays(1));
        BusinessCalendar.DayStatus s = bc.status(date);
        ObjectNode out = base(cal);
        out.put("date", date.toString());
        out.put("day_of_week", date.getDayOfWeek().name());
        out.put("is_business_day", s.businessDay());
        out.put("is_half_day", s.halfDay());
        out.put("reason", s.reason());
        out.put("holiday_name", s.holidayName());
        return out;
    }

    @Transactional(readOnly = true)
    public JsonNode nextBusinessDay(UUID calendarId, LocalDate date, int days) {
        JsonNode cal = calendar(calendarId);
        if (Math.abs(days) > BusinessCalendar.MAX_OFFSET) {
            throw ApiException.unprocessable("offset_too_large",
                    "days must be between -" + BusinessCalendar.MAX_OFFSET + " and " + BusinessCalendar.MAX_OFFSET);
        }
        BusinessCalendar bc = load(cal, date.minusDays(BusinessCalendar.MAX_SCAN_DAYS + 1L),
                date.plusDays(BusinessCalendar.MAX_SCAN_DAYS + 1L));
        LocalDate result = bc.shift(date, days);
        ObjectNode out = base(cal);
        out.put("date", date.toString());
        out.put("days", days);
        out.put("business_day", result.toString());
        out.put("is_half_day", bc.status(result).halfDay());
        out.put("calendar_days", Math.abs(result.toEpochDay() - date.toEpochDay()));
        return out;
    }

    // ------------------------------------------------------------------ import

    @Transactional
    public JsonNode importHolidays(UUID calendarId, int year, String requestedSource) {
        JsonNode cal = calendar(calendarId);
        String source = requestedSource != null && !requestedSource.isBlank()
                ? requestedSource.trim().toUpperCase(java.util.Locale.ROOT)
                : HolidayGenerator.inferSource(cal.path("code").asString());
        if (source == null) {
            throw ApiException.unprocessable("source_required",
                    "No default holiday source for calendar " + cal.path("code").asString() + "; pass one of "
                            + HolidayGenerator.SOURCES.stream().sorted().toList());
        }
        List<GeneratedHoliday> generated = HolidayGenerator.generate(source, year);
        Set<java.time.LocalDate> existing = store.holidayDates(calendarId, year);
        ArrayNode imported = json.createArrayNode();
        int skipped = 0;
        for (GeneratedHoliday h : generated) {
            if (!existing.add(h.date())) {
                skipped++;
                continue;
            }
            ObjectNode v = json.createObjectNode();
            v.put("holiday_calendar_id", calendarId.toString());
            v.put("holiday_date", h.date().toString());
            v.put("name", h.name());
            v.put("holiday_type", h.type());
            v.put("is_half_day", false);
            imported.add(json.readTree(crud.insert("holiday", v)));
        }
        ObjectNode out = base(cal);
        out.put("year", year);
        out.put("source", source);
        out.put("imported", imported.size());
        out.put("skipped", skipped);
        out.set("holidays", imported);
        if (!imported.isEmpty()) {
            ObjectNode change = json.createObjectNode();
            change.put("year", year);
            change.put("source", source);
            change.put("imported", imported.size());
            changed(calendarId, "HOLIDAYS_IMPORTED", change);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private BusinessCalendar load(JsonNode cal, LocalDate from, LocalDate to) {
        Set<DayOfWeek> weekend = BusinessCalendar.parseWeekend(weekendNames(cal.get("weekend_days")));
        return new BusinessCalendar(weekend, store.holidays(UUID.fromString(cal.get("id").asString()), from, to));
    }

    private ObjectNode base(JsonNode cal) {
        ObjectNode out = json.createObjectNode();
        out.put("calendar_id", cal.get("id").asString());
        out.put("calendar_code", cal.path("code").asString());
        return out;
    }

    private JsonNode calendar(UUID id) {
        return store.byId("holiday_calendar", id).orElseThrow(() -> ApiException.notFound("holiday calendar", id));
    }

    private void calendarFor422(UUID id) {
        if (store.byId("holiday_calendar", id).isEmpty()) {
            throw ApiException.unprocessable("calendar_not_found", "Holiday calendar " + id + " does not exist");
        }
    }

    private void changed(UUID calendarId, String change, JsonNode detail) {
        ObjectNode payload = (ObjectNode) calendar(calendarId).deepCopy();
        payload.put("change_type", change);
        if (detail != null) {
            payload.set("change", detail);
        }
        outbox.publish("platform.holiday_calendar.changed", "holiday_calendar", calendarId, payload);
    }

    private static void validateTimezone(ObjectNode v) {
        if (v.hasNonNull("timezone")) {
            try {
                ZoneId.of(v.get("timezone").asString());
            } catch (DateTimeException e) {
                throw ApiException.unprocessable("invalid_timezone", "timezone must be an IANA zone id, e.g. Africa/Nairobi");
            }
        }
    }

    /** Accepts weekday names (full or 3-letter, any case); returns the canonical array of names. */
    ArrayNode canonicalWeekend(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            throw ApiException.unprocessable("invalid_weekend_days", "weekend_days is required, e.g. [\"SATURDAY\",\"SUNDAY\"]");
        }
        Set<DayOfWeek> days = BusinessCalendar.parseWeekend(weekendNames(raw));
        ArrayNode out = json.createArrayNode();
        BusinessCalendar.names(days).forEach(out::add);
        return out;
    }

    static List<String> weekendNames(JsonNode raw) {
        List<String> names = new ArrayList<>();
        if (raw == null || raw.isNull()) {
            return names;
        }
        if (!raw.isArray()) {
            throw ApiException.unprocessable("invalid_weekend_days", "weekend_days must be an array of weekday names");
        }
        raw.forEach(n -> names.add(n.asString()));
        return names;
    }
}
