package io.meridian.businessday.infrastructure;

import io.meridian.businessday.domain.BusinessCalendar;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Resolves business calendars. With a holiday calendar id the weekend days and holidays come from
 * reference-data-service ({@code /v1/reference/holiday-calendars/{id}} and {@code /v1/reference/holidays});
 * without one the configured default weekend ({@code meridian.business-day.weekend-days}) and holiday list
 * ({@code meridian.business-day.holidays}, ISO dates) apply.
 */
@Component
public class CalendarProvider {
    static final String REFERENCE_DATA = "reference-data-service";
    private static final int MAX_PAGES = 50;

    private final ServiceClient services;
    private final Set<DayOfWeek> defaultWeekend;
    private final List<LocalDate> defaultHolidays;

    public CalendarProvider(ServiceClient services,
                            @Value("${meridian.business-day.weekend-days:SATURDAY,SUNDAY}") List<String> weekend,
                            @Value("${meridian.business-day.holidays:}") List<String> holidays) {
        this.services = services;
        Set<DayOfWeek> w = EnumSet.noneOf(DayOfWeek.class);
        weekend.stream().filter(s -> !s.isBlank()).forEach(s -> w.add(DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT))));
        this.defaultWeekend = w;
        this.defaultHolidays = holidays.stream().filter(s -> !s.isBlank()).map(s -> LocalDate.parse(s.trim())).toList();
    }

    public BusinessCalendar calendar(UUID holidayCalendarId) {
        if (holidayCalendarId == null) {
            return new BusinessCalendar(defaultWeekend, defaultHolidays);
        }
        JsonNode cal = services.get(REFERENCE_DATA, "/v1/reference/holiday-calendars/" + holidayCalendarId);
        if (cal == null || !cal.has("weekend_days")) {
            throw new ApiException(502, "invalid_calendar", "Holiday calendar " + holidayCalendarId
                    + " returned no weekend definition");
        }
        Set<DayOfWeek> weekend = EnumSet.noneOf(DayOfWeek.class);
        for (String d : weekendDays(cal.get("weekend_days"))) {
            try {
                weekend.add(DayOfWeek.valueOf(d.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ApiException(502, "invalid_calendar", "Holiday calendar " + holidayCalendarId
                        + " has an invalid weekend day " + d);
            }
        }
        List<LocalDate> holidays = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String path = "/v1/reference/holidays?holiday_calendar_id=" + holidayCalendarId + "&limit=200"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode res = services.get(REFERENCE_DATA, path);
            if (res == null) {
                break;
            }
            for (JsonNode h : res.path("data")) {
                // half days are still business days
                if (!h.path("is_half_day").asBoolean(false) && h.hasNonNull("holiday_date")) {
                    holidays.add(LocalDate.parse(h.get("holiday_date").asString()));
                }
            }
            if (!res.path("has_more").asBoolean(false) || !res.hasNonNull("next_cursor")) {
                break;
            }
            cursor = res.get("next_cursor").asString();
        }
        return new BusinessCalendar(weekend, holidays);
    }

    /**
     * reference-data-service (platform domain) still types {@code weekend_days} as an object and stores
     * {@code {"days": [...]}}; a plain array is accepted too, so this keeps working once that contract is hardened.
     */
    static List<String> weekendDays(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        JsonNode list = node.isArray() ? node : node.path("days");
        list.forEach(v -> out.add(v.asString()));
        return out;
    }
}
