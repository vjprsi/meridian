package io.meridian.businessday.application;

import io.meridian.businessday.domain.BusinessCalendar;
import io.meridian.businessday.domain.BusinessDayState;
import io.meridian.businessday.infrastructure.CalendarProvider;
import io.meridian.businessday.infrastructure.JsonValues;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Business date per legal entity: open, cut-off, date roll (with holiday calendar and four-eyes forced roll)
 * and reopen. The current date of an entity is the single row with {@code is_current = true}; dates only move
 * forward, so nothing can be posted to a date in the past once rolled.
 */
@Service
public class BusinessDayService {
    private final Rows rows;
    private final CrudEngine crud;
    private final CalendarProvider calendars;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public BusinessDayService(Rows rows, CrudEngine crud, CalendarProvider calendars, OutboxWriter outbox, JsonMapper json) {
        this.rows = rows;
        this.crud = crud;
        this.calendars = calendars;
        this.outbox = outbox;
        this.json = json;
    }

    /** Opens the first business date of a legal entity (later dates are opened by the roll). */
    @Transactional
    public String open(JsonNode req) {
        UUID entity = uuid(req, "legal_entity_id", true);
        String dateText = JsonValues.text(req, "business_date");
        if (dateText == null) {
            throw ApiException.unprocessable("business_date_required", "business_date is required");
        }
        LocalDate date = LocalDate.parse(dateText);
        UUID calendarId = uuid(req, "holiday_calendar_id", false);
        lockEntity(entity);
        if (current(entity).isPresent()) {
            throw ApiException.conflict("business_day_already_open",
                    "Legal entity " + entity + " already has an open business date; use the date roll");
        }
        rows.queryOne("""
                select json_build_object('d', max(business_date))::text from business_day where legal_entity_id = ?""", entity)
                .map(n -> n.get("d")).filter(n -> !n.isNull())
                .map(n -> LocalDate.parse(n.asString()))
                .filter(last -> !date.isAfter(last))
                .ifPresent(last -> {
                    throw ApiException.conflict("backdated_business_date",
                            "Business date " + date + " is not after the last business date " + last);
                });
        BusinessCalendar cal = calendars.calendar(calendarId);
        if (!cal.isBusinessDay(date)) {
            throw ApiException.unprocessable("not_a_business_day", date + " is not a business day in the calendar");
        }
        String row = insertDay(entity, date, calendarId, cal, JsonValues.text(req, "notes"));
        publish("operations.business_day.opened", row);
        return row;
    }

    public String current(UUID legalEntityId, boolean any) {
        if (legalEntityId != null) {
            return current(legalEntityId).orElseThrow(() -> ApiException.notFound("current business day of legal entity", legalEntityId));
        }
        List<JsonNode> all = rows.query("select row_to_json(b)::text from business_day b where is_current");
        if (all.isEmpty()) {
            throw new ApiException(404, "not_found", "No business date is open");
        }
        if (all.size() > 1 && !any) {
            throw ApiException.badRequest("legal_entity_id_required",
                    "Several legal entities have an open business date; pass ?legal_entity_id=");
        }
        return all.getFirst().toString();
    }

    @Transactional
    public String cutOff(UUID id, String reason) {
        JsonNode day = rows.require("business_day", id, true);
        BusinessDayState.of(day.get("state").asString()).requireCutOff();
        requireCurrent(day);
        String row = rows.jdbc().sql("""
                update business_day set state = 'CUT_OFF', cut_off_at = now(), updated_by = ?,
                       notes = coalesce(?, notes)
                where id = ? returning row_to_json(business_day)::text""")
                .params(TenantContext.principal(), reason, id).query(String.class).single();
        publish("operations.business_day.cut_off", row);
        return row;
    }

    /**
     * Closes the current date and opens the next business date from the calendar. Requires a completed EOD:
     * SUCCEEDED rolls normally; PARTIALLY_SUCCEEDED (only non-critical jobs failed) needs {@code forced} and a
     * checker different from the user who ran EOD; FAILED / CANCELLED / running EOD block the roll.
     */
    @Transactional
    public String roll(UUID id, boolean forced, String reason) {
        JsonNode day = rows.require("business_day", id, true);
        BusinessDayState.of(day.get("state").asString()).requireRoll();
        requireCurrent(day);
        JsonNode run = rows.queryOne("""
                select row_to_json(r)::text from eod_run r where business_day_id = ?
                order by created_at desc, id desc limit 1""", id)
                .orElseThrow(() -> ApiException.conflict("eod_not_run", "No EOD run exists for this business date"));
        String status = run.get("status").asString();
        boolean forcedRoll = false;
        switch (status) {
            case "SUCCEEDED" -> { }
            case "PARTIALLY_SUCCEEDED" -> {
                if (!forced) {
                    throw ApiException.conflict("eod_incomplete",
                            "Non-critical EOD jobs failed; retry or skip them, or force the roll (four-eyes)");
                }
                if (Objects.equals(TenantContext.principal(), run.path("triggered_by").asString(null))) {
                    throw ApiException.unprocessable("four_eyes_violation",
                            "A forced roll must be approved by a different user than the one who ran EOD");
                }
                forcedRoll = true;
            }
            case "QUEUED", "RUNNING" -> throw ApiException.conflict("eod_in_progress", "EOD is still running");
            default -> throw ApiException.conflict("eod_failed",
                    "EOD run " + run.get("id").asString() + " is " + status + "; restart it before rolling");
        }
        UUID entity = UUID.fromString(day.get("legal_entity_id").asString());
        LocalDate from = LocalDate.parse(day.get("business_date").asString());
        UUID calendarId = day.hasNonNull("holiday_calendar_id")
                ? UUID.fromString(day.get("holiday_calendar_id").asString()) : null;
        BusinessCalendar cal = calendars.calendar(calendarId);
        LocalDate to = cal.nextBusinessDay(from);
        rows.queryOne("""
                select row_to_json(b)::text from business_day b where legal_entity_id = ? and business_date >= ?
                limit 1""", entity, to).ifPresent(b -> {
            throw ApiException.conflict("date_conflict", "Business date " + b.get("business_date").asString()
                    + " already exists for the legal entity");
        });
        String closed = rows.jdbc().sql("""
                update business_day set state = 'CLOSED', closed_at = now(), is_current = false,
                       next_business_date = ?, updated_by = ?, notes = coalesce(?, notes)
                where id = ? returning row_to_json(business_day)::text""")
                .params(to, TenantContext.principal(), reason, id).query(String.class).single();
        String opened = insertDay(entity, to, calendarId, cal, null);
        ObjectNode roll = json.createObjectNode();
        roll.put("legal_entity_id", entity.toString());
        roll.put("from_business_date", from.toString());
        roll.put("to_business_date", to.toString());
        roll.put("eod_run_id", run.get("id").asString());
        roll.put("forced", forcedRoll);
        if (forcedRoll) {
            roll.put("approved_by", TenantContext.principal());
        }
        String rollRow = crud.insert("date_roll", roll);
        publish("operations.business_day.closed", closed);
        publish("operations.business_day.opened", opened);
        JsonNode r = json.readTree(rollRow);
        outbox.publish("operations.date_roll.completed", "date_roll", UUID.fromString(r.get("id").asString()), r);
        return rollRow;
    }

    /**
     * Reopens the most recently closed date (e.g. to correct EOD). The successor date must still be OPEN with no
     * EOD activity; it is withdrawn. Four-eyes: the approver must not be the user who rolled the date.
     */
    @Transactional
    public String reopen(UUID id, String reason) {
        JsonNode day = rows.require("business_day", id, true);
        BusinessDayState.of(day.get("state").asString()).requireReopen();
        UUID entity = UUID.fromString(day.get("legal_entity_id").asString());
        JsonNode successor = current(entity).map(rows::tree)
                .orElseThrow(() -> ApiException.conflict("no_successor", "The legal entity has no open business date"));
        if (!successor.path("business_date").asString().equals(day.path("next_business_date").asString(""))) {
            throw ApiException.conflict("not_latest_closed_day", "Only the most recently closed business date can be reopened");
        }
        UUID successorId = UUID.fromString(successor.get("id").asString());
        rows.require("business_day", successorId, true);
        boolean activity = !"OPEN".equals(successor.get("state").asString())
                || rows.jdbc().sql("select count(*) from eod_run where business_day_id = ?").param(successorId)
                        .query(Long.class).single() > 0;
        if (activity) {
            throw ApiException.conflict("successor_in_progress",
                    "Business date " + successor.get("business_date").asString() + " already started processing");
        }
        rows.queryOne("""
                select row_to_json(r)::text from date_roll r where legal_entity_id = ? and from_business_date = ?
                order by created_at desc limit 1""", entity, LocalDate.parse(day.get("business_date").asString()))
                .ifPresent(r -> {
                    if (Objects.equals(r.path("created_by").asString(null), TenantContext.principal())) {
                        throw ApiException.unprocessable("four_eyes_violation",
                                "Reopening must be approved by a different user than the one who rolled the date");
                    }
                });
        rows.update("delete from business_day where id = ?", successorId);
        String row = rows.jdbc().sql("""
                update business_day set state = 'OPEN', is_current = true, closed_at = null, cut_off_at = null,
                       next_business_date = null, updated_by = ?, notes = ?
                where id = ? returning row_to_json(business_day)::text""")
                .params(TenantContext.principal(), "Reopened: " + reason, id).query(String.class).single();
        publish("operations.business_day.opened", row);
        return row;
    }

    private String insertDay(UUID entity, LocalDate date, UUID calendarId, BusinessCalendar cal, String notes) {
        BusinessCalendar.PeriodEnd pe = cal.periodEnd(date);
        ObjectNode v = json.createObjectNode();
        v.put("id", Ids.newId().toString());
        v.put("legal_entity_id", entity.toString());
        v.put("business_date", date.toString());
        v.put("state", "OPEN");
        v.put("is_current", true);
        if (calendarId != null) {
            v.put("holiday_calendar_id", calendarId.toString());
        }
        v.put("is_month_end", pe.monthEnd());
        v.put("is_quarter_end", pe.quarterEnd());
        v.put("is_year_end", pe.yearEnd());
        if (notes != null) {
            v.put("notes", notes);
        }
        return crud.insert("business_day", v);
    }

    private java.util.Optional<String> current(UUID entity) {
        return rows.jdbc().sql("select row_to_json(b)::text from business_day b where legal_entity_id = ? and is_current")
                .param(entity).query(String.class).optional();
    }

    /** Serialises concurrent opens for one entity (transaction-scoped advisory lock). */
    private void lockEntity(UUID entity) {
        rows.jdbc().sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l").param("bday:" + entity)
                .query(Integer.class).single();
    }

    private static void requireCurrent(JsonNode day) {
        if (!day.path("is_current").asBoolean(false)) {
            throw ApiException.conflict("not_current_business_day", "Business date " + day.get("business_date").asString()
                    + " is not the current date of its legal entity");
        }
    }

    private void publish(String type, String row) {
        JsonNode n = json.readTree(row);
        outbox.publish(type, "business_day", UUID.fromString(n.get("id").asString()), n);
    }

    private static UUID uuid(JsonNode n, String f, boolean required) {
        String v = JsonValues.text(n, f);
        if (v == null) {
            if (required) {
                throw ApiException.unprocessable(f + "_required", f + " is required");
            }
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_value", f + " must be a UUID");
        }
    }
}
