package io.meridian.routing.application;

import static io.meridian.routing.infrastructure.Rows.bool;
import static io.meridian.routing.infrastructure.Rows.dec;
import static io.meridian.routing.infrastructure.Rows.integer;
import static io.meridian.routing.infrastructure.Rows.str;
import static io.meridian.routing.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.routing.config.RoutingProperties;
import io.meridian.routing.domain.CutoffCalculator;
import io.meridian.routing.domain.RoutingEngine;
import io.meridian.routing.infrastructure.Downstream;
import io.meridian.routing.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Rail catalogue: validation, suspend / resume, monitoring metrics (DEGRADED), cut-offs and next execution date. */
@Service
public class RailService {
    public static final String RAIL = "payment_rail";
    private static final Set<String> SETTLEMENT_MODELS = Set.of("RTGS", "DNS", "PREFUNDED", "CORRESPONDENT");

    private final Rows rows;
    private final Events events;
    private final Downstream downstream;
    private final RoutingProperties props;
    private final Clock clock;

    public RailService(Rows rows, Events events, Downstream downstream, RoutingProperties props, Clock clock) {
        this.rows = rows;
        this.events = events;
        this.downstream = downstream;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ validation

    /** Validates a rail create / update body (only fields present are checked). */
    public void validate(JsonNode body, boolean create) {
        JsonNode code = body.get("code");
        if (code != null && !code.isNull() && !code.asString().matches("[A-Z0-9_]{2,32}")) {
            throw ApiException.unprocessable("invalid_rail_code", "code must be 2-32 upper-case letters, digits or _");
        }
        JsonNode ccys = body.get("currencies");
        if (create || ccys != null && !ccys.isNull()) {
            if (ccys == null || !ccys.isArray() || ccys.isEmpty()) {
                throw ApiException.unprocessable("currencies_required", "currencies must list at least one ISO 4217 code");
            }
            for (JsonNode c : ccys) {
                if (!c.isString() || !c.asString().matches("[A-Z]{3}")) {
                    throw ApiException.unprocessable("invalid_currency", "currencies must be ISO 4217 codes: " + c);
                }
            }
        }
        JsonNode model = body.get("settlement_model");
        if (model != null && !model.isNull() && !SETTLEMENT_MODELS.contains(model.asString())) {
            throw ApiException.unprocessable("invalid_settlement_model", "settlement_model must be one of " + SETTLEMENT_MODELS);
        }
        for (String f : List.of("max_amount", "cost_per_payment")) {
            BigDecimal v = dec(body, f);
            if (v != null && v.signum() < 0) {
                throw ApiException.unprocessable("invalid_" + f, f + " must not be negative");
            }
        }
    }

    /** Validates a cut-off create / update body. */
    public void validateCutoff(JsonNode body) {
        String t = str(body, "cutoff_time");
        if (t != null && !t.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw ApiException.unprocessable("invalid_cutoff_time", "cutoff_time must be HH:MM (24h)");
        }
        String tz = str(body, "timezone");
        if (tz != null) {
            try {
                ZoneId.of(tz);
            } catch (Exception e) {
                throw ApiException.unprocessable("invalid_timezone", "timezone must be an IANA zone id");
            }
        }
        JsonNode days = body.get("days_of_week");
        if (days != null && days.isArray()) {
            for (JsonNode d : days) {
                try {
                    java.time.DayOfWeek.valueOf(d.asString().toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    throw ApiException.unprocessable("invalid_days_of_week", "days_of_week entries must be MONDAY..SUNDAY");
                }
            }
        }
        UUID rail = uuid(body, "payment_rail_id");
        if (rail != null) {
            rows.get(RAIL, rail);
        }
    }

    // ------------------------------------------------------------------ status

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        ObjectNode rail = rows.lock(RAIL, id);
        String status = str(rail, "status");
        if ("SUSPENDED".equals(status) || "RETIRED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Rail is " + status + " and cannot be suspended");
        }
        return changeStatus(id, "SUSPENDED", reason);
    }

    @Transactional
    public ObjectNode resume(UUID id, String reason) {
        ObjectNode rail = rows.lock(RAIL, id);
        String status = str(rail, "status");
        if (!"SUSPENDED".equals(status) && !"DEGRADED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only SUSPENDED or DEGRADED rails can be resumed (rail is "
                    + status + ")");
        }
        return changeStatus(id, "ACTIVE", reason == null ? "resumed" : reason);
    }

    /** Records monitoring metrics; an ACTIVE rail below the success threshold becomes DEGRADED and recovers above it. */
    @Transactional
    public ObjectNode metrics(UUID id, BigDecimal successRate, Integer p95) {
        if (successRate == null || successRate.signum() < 0 || successRate.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid_success_rate", "success_rate_7d must be between 0 and 1");
        }
        if (p95 == null || p95 < 0) {
            throw ApiException.unprocessable("invalid_latency", "p95_latency_ms must not be negative");
        }
        ObjectNode rail = rows.lock(RAIL, id);
        ObjectNode v = rows.object();
        v.put("success_rate_7d", successRate.toPlainString());
        v.put("p95_latency_ms", p95);
        rows.update(RAIL, id, v);
        rows.execute("update payment_rail set metrics_updated_at = now() where id = ?", id);
        String status = str(rail, "status");
        boolean below = successRate.compareTo(props.degradedThreshold()) < 0;
        if ("ACTIVE".equals(status) && below) {
            return changeStatus(id, "DEGRADED", "success rate " + successRate.toPlainString() + " below "
                    + props.degradedThreshold().toPlainString());
        }
        if ("DEGRADED".equals(status) && !below) {
            return changeStatus(id, "ACTIVE", "success rate recovered to " + successRate.toPlainString());
        }
        return rows.get(RAIL, id);
    }

    private ObjectNode changeStatus(UUID id, String status, String reason) {
        ObjectNode v = rows.object();
        v.put("status", status);
        v.put("status_reason", reason == null ? null : truncate(reason, 255));
        ObjectNode row = rows.update(RAIL, id, v);
        events.publish("payments.payment_rail.status_changed", RAIL, row);
        return row;
    }

    // ------------------------------------------------------------------ cut-offs

    @Transactional(readOnly = true)
    public ObjectNode nextExecution(UUID railId, String paymentType, String currency, OffsetDateTime at,
                                    LocalDate requested) {
        ObjectNode rail = rows.get(RAIL, railId);
        return nextExecution(rail, paymentType, currency, at, requested);
    }

    ObjectNode nextExecution(ObjectNode rail, String paymentType, String currency, OffsetDateTime at, LocalDate requested) {
        UUID railId = uuid(rail, "id");
        ObjectNode cutoffRow = rows.queryOne("""
                select row_to_json(t)::text from rail_cutoff t
                where payment_rail_id = ? and (payment_type is null or payment_type = ?) and (currency is null or currency = ?)
                order by (payment_type is not null) desc, (currency is not null) desc, cutoff_time asc limit 1""",
                railId, paymentType, currency).orElse(null);
        CutoffCalculator.Cutoff cutoff = null;
        if (cutoffRow != null) {
            List<String> days = new ArrayList<>();
            JsonNode d = cutoffRow.get("days_of_week");
            if (d != null && d.isArray()) {
                d.forEach(x -> days.add(x.asString()));
            }
            cutoff = new CutoffCalculator.Cutoff(str(cutoffRow, "cutoff_time"), str(cutoffRow, "timezone"), days,
                    bool(cutoffRow, "next_day_rollover"));
        }
        CutoffCalculator.Window window = null;
        JsonNode hours = rail.get("operating_hours");
        if (hours != null && hours.isObject() && hours.hasNonNull("open") && hours.hasNonNull("close")) {
            window = new CutoffCalculator.Window(hours.get("open").asString(), hours.get("close").asString(),
                    str(hours, "timezone"));
        }
        CutoffCalculator.Result r;
        try {
            r = CutoffCalculator.compute(at == null ? clock.instant() : at.toInstant(), bool(rail, "is_instant"), window,
                    cutoff, requested, downstream.calendar(uuid(rail, "holiday_calendar_id")));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_cutoff_configuration", e.getMessage());
        }
        ObjectNode out = rows.object();
        out.put("payment_rail_id", railId.toString());
        out.put("rail_code", str(rail, "code"));
        out.put("is_instant", bool(rail, "is_instant"));
        out.put("payment_type", paymentType);
        out.put("currency", currency);
        out.put("execution_date", r.executionDate().toString());
        out.put("cutoff_at", r.cutoffAt() == null ? null : r.cutoffAt().toString());
        out.put("after_cutoff", r.afterCutoff());
        out.put("is_business_day", r.todayIsBusinessDay());
        out.put("immediate", r.immediate());
        out.put("timezone", r.timezone());
        out.put("cutoff_id", cutoffRow == null ? null : str(cutoffRow, "id"));
        return out;
    }

    // ------------------------------------------------------------------ engine views

    static RoutingEngine.Rail toEngine(JsonNode r) {
        Set<String> ccys = new HashSet<>();
        JsonNode c = r.get("currencies");
        if (c != null && c.isArray()) {
            c.forEach(x -> ccys.add(x.asString()));
        }
        return new RoutingEngine.Rail(uuid(r, "id"), str(r, "code"), str(r, "rail_type"), str(r, "status"), ccys,
                str(r, "country"), dec(r, "max_amount"), dec(r, "cost_per_payment"), integer(r, "settlement_minutes"),
                bool(r, "is_instant"), dec(r, "success_rate_7d"), integer(r, "p95_latency_ms"));
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
