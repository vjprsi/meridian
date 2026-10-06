package io.meridian.crm.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Adapters to other Meridian services: holiday calendars (reference-data-service) and postings (ledger-service). */
@Component
public class ExternalServices {
    private static final Logger log = LoggerFactory.getLogger(ExternalServices.class);
    static final String REFERENCE_DATA = "reference-data-service";
    static final String LEDGER = "ledger-service";

    private final ServiceClient client;

    public ExternalServices(ServiceClient client) {
        this.client = client;
    }

    /**
     * Holiday dates of a calendar. Fail-open: when the calendar cannot be read the SLA clock counts the day as a
     * working day (deadlines become earlier, never later) and the problem is logged.
     */
    public Set<LocalDate> holidays(UUID calendarId) {
        Set<LocalDate> out = new HashSet<>();
        if (calendarId == null) {
            return out;
        }
        try {
            JsonNode page = client.get(REFERENCE_DATA, "/v1/reference/holidays?holiday_calendar_id=" + calendarId + "&limit=200");
            JsonNode data = page == null ? null : page.get("data");
            if (data != null && data.isArray()) {
                data.forEach(h -> {
                    if (h.hasNonNull("holiday_date") && !h.path("is_half_day").asBoolean(false)) {
                        out.add(LocalDate.parse(h.get("holiday_date").asString()));
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("Holiday calendar {} unavailable, counting all working days: {}", calendarId, e.getMessage());
        }
        return out;
    }

    /** Posts a balanced journal entry; the key makes retries exactly-once at the ledger. */
    public JsonNode postJournalEntry(Object body, String idempotencyKey) {
        return client.post(LEDGER, "/v1/journal-entries", body, idempotencyKey);
    }
}
