package io.meridian.billing.application;

import io.meridian.billing.domain.UsageMetrics;
import io.meridian.billing.infrastructure.BillingStore;
import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Usage metering: append-only daily records de-duplicated on the reporter's idempotency key. */
@Service
public class UsageService {
    /** Reporters may be at most one day ahead (time zones); longer windows look like clock errors. */
    private static final int MAX_FUTURE_DAYS = 1;
    private static final int MAX_SUMMARY_DAYS = 366;

    private final CrudEngine crud;
    private final BillingStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public UsageService(CrudEngine crud, BillingStore store, OutboxWriter outbox, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    public record Recorded(String row, boolean replayed) {}

    @Transactional
    public Recorded record(Object body, String idempotencyKey, LocalDate today) {
        var existing = store.usageByKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Recorded(existing.get(), true);
        }
        ObjectNode values = crud.toColumns("usage_record", body);
        if (Rows.longOr(values, "quantity", -1) < 0) {
            throw ApiException.unprocessable("invalid_quantity", "quantity must be zero or positive");
        }
        LocalDate day = Rows.date(values, "period_date");
        if (day.isAfter(today.plusDays(MAX_FUTURE_DAYS))) {
            throw ApiException.unprocessable("future_usage", "period_date " + day + " is in the future");
        }
        String service = Rows.text(values, "service_name");
        if (service == null || service.isBlank()) {
            throw ApiException.unprocessable("service_name_required", "service_name must not be blank");
        }
        values.put("idempotency_key", idempotencyKey);
        String row = crud.insert("usage_record", values);
        outbox.publish("platform.usage_record.recorded", "usage_record",
                UUID.fromString(json.readTree(row).get("id").asString()), json.readTree(row));
        return new Recorded(row, false);
    }

    @Transactional(readOnly = true)
    public ObjectNode summary(String fromParam, String toParam, String serviceName) {
        LocalDate from = parseDate("from", fromParam);
        LocalDate to = parseDate("to", toParam);
        if (to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_period", "to must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_SUMMARY_DAYS) {
            throw ApiException.unprocessable("period_too_long", "A summary covers at most " + MAX_SUMMARY_DAYS + " days");
        }
        ObjectNode out = json.createObjectNode();
        out.put("from", from.toString());
        out.put("to", to.toString());
        if (serviceName != null) {
            out.put("service_name", serviceName);
        }
        ArrayNode metrics = out.putArray("metrics");
        for (BillingStore.MetricTotal t : store.usageTotals(from, to, serviceName)) {
            ObjectNode m = metrics.addObject();
            m.put("metric", t.metric());
            m.put("total_quantity", t.total());
            m.put("peak_daily_quantity", t.peakDaily());
            m.put("days_reported", t.days());
            m.put("billable_quantity", UsageMetrics.isGauge(t.metric()) ? t.peakDaily() : t.total());
            m.put("aggregation", UsageMetrics.isGauge(t.metric()) ? "PEAK_DAILY" : "SUM");
        }
        return out;
    }

    private static LocalDate parseDate(String name, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("missing_parameter", "Query parameter '" + name + "' (YYYY-MM-DD) is required");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("invalid_parameter", "Query parameter '" + name + "' must be a date (YYYY-MM-DD)");
        }
    }
}
