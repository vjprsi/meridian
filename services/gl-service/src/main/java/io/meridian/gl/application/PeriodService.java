package io.meridian.gl.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.gl.domain.PeriodCalendar;
import io.meridian.gl.domain.PeriodLifecycle;
import io.meridian.gl.domain.PeriodStatus;
import io.meridian.gl.infrastructure.GlStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Accounting periods and the close: create (non-overlapping per legal entity and type), close as maker (runs the
 * checklist in a period_close_run; the period becomes SOFT_CLOSED only when no blocking step fails), approve the
 * close as a different principal (CLOSED), reopen with a reason.
 */
@Service
public class PeriodService {
    private static final String T = "accounting_period";

    private final GlStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public PeriodService(GlStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        UUID le = Fields.requireUuid(v, "legal_entity_id");
        PeriodCalendar.PeriodType type = Fields.enumValue(v, "period_type", PeriodCalendar.PeriodType.class);
        if (type == null) {
            throw Fields.invalid("period_type", "required", "period_type is required");
        }
        LocalDate start = Fields.requireDate(v, "start_date");
        LocalDate end = Fields.requireDate(v, "end_date");
        if (v.hasNonNull("status") && !"OPEN".equals(v.get("status").asString())) {
            throw ApiException.unprocessable("invalid_initial_status", "A period is always created OPEN");
        }
        PeriodCalendar.validate(type, start, end);
        store.advisoryLock("accounting_period:" + TenantContext.require() + ":" + le + ":" + type);
        store.row("""
                select row_to_json(t)::text from accounting_period t
                where t.legal_entity_id = ? and t.period_type = ? and t.start_date <= ? and t.end_date >= ? limit 1""",
                le, type.name(), end, start).ifPresent(p -> {
                    throw ApiException.conflict("overlapping_period", "The period overlaps " + type + " period "
                            + p.get("start_date").asString() + ".." + p.get("end_date").asString());
                });
        v.put("status", PeriodStatus.OPEN.name());
        return crud.insert(T, v);
    }

    /** Maker: runs the close checklist; on success the period becomes SOFT_CLOSED awaiting the checker. */
    @Transactional
    public String close(UUID id) {
        JsonNode period = store.requireLocked(T, id);
        PeriodStatus from = PeriodStatus.valueOf(period.get("status").asString());
        PeriodStatus to = PeriodLifecycle.next(from, PeriodLifecycle.Action.CLOSE);

        UUID le = UUID.fromString(period.get("legal_entity_id").asString());
        LocalDate start = LocalDate.parse(period.get("start_date").asString());
        LocalDate end = LocalDate.parse(period.get("end_date").asString());
        OffsetDateTime startedAt = OffsetDateTime.now();

        ArrayNode checklist = json.createArrayNode();
        int failed = 0;
        int warnings = 0;

        long unapproved = store.count("""
                select count(*) from gl_journal where legal_entity_id = ? and value_date between ? and ?
                  and journal_type <> 'AUTO_AGGREGATE' and approved_by is null""", le, start, end);
        failed += step(checklist, "manual_journals_approved", unapproved == 0 ? "PASSED" : "FAILED",
                unapproved == 0 ? "All manual journals are approved" : unapproved + " manual journal(s) await approval");

        long unbalanced = store.count("""
                select count(*) from (
                    select l.gl_journal_id, l.currency from gl_journal_line l join gl_journal j on j.id = l.gl_journal_id
                    where j.legal_entity_id = ? and j.value_date between ? and ?
                    group by l.gl_journal_id, l.currency
                    having sum(case when l.direction = 'DEBIT' then l.amount else -l.amount end) <> 0) x""",
                le, start, end);
        failed += step(checklist, "journals_balanced", unbalanced == 0 ? "PASSED" : "FAILED",
                unbalanced == 0 ? "Every journal balances per currency" : unbalanced + " unbalanced journal/currency pair(s)");

        List<JsonNode> tb = store.rows("""
                select json_build_object('currency', currency, 'debits', sum(debits), 'credits', sum(credits))::text
                from gl_balance where accounting_period_id = ? group by currency order by currency""", id);
        StringBuilder off = new StringBuilder();
        for (JsonNode c : tb) {
            if (new BigDecimal(c.get("debits").asString()).compareTo(new BigDecimal(c.get("credits").asString())) != 0) {
                off.append(off.isEmpty() ? "" : ", ").append(c.get("currency").asString());
            }
        }
        failed += step(checklist, "trial_balance_balanced", off.isEmpty() ? "PASSED" : "FAILED",
                off.isEmpty() ? "Debits equal credits in " + tb.size() + " currency(ies)" : "Out of balance in " + off);

        long ecl = store.count("""
                select count(*) from ecl_provision where legal_entity_id = ? and reporting_date between ? and ?""",
                le, start, end);
        if (ecl == 0) {
            warnings++;
        }
        step(checklist, "ecl_provisions", ecl == 0 ? "WARNING" : "PASSED",
                ecl == 0 ? "No IFRS 9 provisions calculated in the period" : ecl + " provision(s) calculated");
        step(checklist, "reconciliation_checks", "SKIPPED", "Breaks are tracked by reconciliation-service");

        ObjectNode steps = json.createObjectNode();
        steps.set("checklist", checklist);
        steps.put("failed", failed);
        steps.put("warnings", warnings);

        ObjectNode run = json.createObjectNode();
        UUID runId = Ids.newId();
        run.put("id", runId.toString());
        run.put("accounting_period_id", id.toString());
        run.put("status", failed == 0 ? "SUCCEEDED" : "FAILED");
        run.put("started_at", startedAt.toString());
        run.put("finished_at", OffsetDateTime.now().toString());
        run.set("steps", steps);
        if (failed > 0) {
            run.put("error", failed + " blocking checklist step(s) failed; the period stays " + from);
        }
        String runRow = crud.insert("period_close_run", run);

        if (failed == 0) {
            store.update("update accounting_period set status = ?, closed_by = ?, closed_at = now(), approved_by = null, "
                    + "updated_by = ? where id = ?", to.name(), TenantContext.principal(), TenantContext.principal(), id);
        }
        outbox.publish("ledger.period_close_run.completed", "period_close_run", runId, json.readTree(runRow));
        return runRow;
    }

    /** Checker: four-eyes approval of a soft close makes the period CLOSED. */
    @Transactional
    public String approveClose(UUID id, String comment) {
        JsonNode period = store.requireLocked(T, id);
        PeriodStatus to = PeriodLifecycle.next(PeriodStatus.valueOf(period.get("status").asString()),
                PeriodLifecycle.Action.APPROVE_CLOSE);
        if (TenantContext.principal().equals(Fields.text(period, "closed_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The principal who closed the period (maker) cannot approve the close");
        }
        store.row("""
                select row_to_json(r)::text from period_close_run r where r.accounting_period_id = ?
                order by r.id desc limit 1""", id)
                .filter(r -> "SUCCEEDED".equals(r.get("status").asString()))
                .orElseThrow(() -> ApiException.conflict("close_run_not_succeeded",
                        "The latest close run of the period did not succeed"));
        store.update("update accounting_period set status = ?, approved_by = ?, updated_by = ? where id = ?",
                to.name(), TenantContext.principal(), TenantContext.principal(), id);
        JsonNode row = store.require(T, id);
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        if (comment != null) {
            payload.put("approval_comment", comment);
        }
        outbox.publish("ledger.accounting_period.closed", T, id, payload);
        return row.toString();
    }

    @Transactional
    public String reopen(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw Fields.invalid("reason", "required", "A reason is required to reopen a period");
        }
        JsonNode period = store.requireLocked(T, id);
        PeriodStatus to = PeriodLifecycle.next(PeriodStatus.valueOf(period.get("status").asString()),
                PeriodLifecycle.Action.REOPEN);
        store.row("""
                select row_to_json(t)::text from accounting_period t where t.legal_entity_id = ?::uuid
                  and t.period_type = ? and t.start_date > ?::date and t.status = 'CLOSED' limit 1""",
                period.get("legal_entity_id").asString(), period.get("period_type").asString(),
                period.get("end_date").asString()).ifPresent(p -> {
                    throw ApiException.conflict("later_period_closed", "A later " + p.get("period_type").asString()
                            + " period (" + p.get("start_date").asString() + ") is closed; reopen it first");
                });
        store.update("""
                update accounting_period set status = ?, closed_at = null, closed_by = null, approved_by = null,
                       updated_by = ? where id = ?""", to.name(), TenantContext.principal(), id);
        JsonNode row = store.require(T, id);
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        payload.put("reopen_reason", reason);
        payload.put("previous_status", period.get("status").asString());
        outbox.publish("ledger.accounting_period.reopened", T, id, payload);
        return row.toString();
    }

    /** True when debits equal credits in every currency of the period's GL balances. */
    @Transactional(readOnly = true)
    public boolean trialBalanceBalanced(UUID periodId) {
        store.require(T, periodId);
        return store.count("""
                select count(*) from (select currency from gl_balance where accounting_period_id = ?
                                      group by currency having sum(debits) <> sum(credits)) x""", periodId) == 0;
    }

    private int step(ArrayNode checklist, String name, String status, String detail) {
        ObjectNode s = checklist.addObject();
        s.put("step", name);
        s.put("status", status);
        s.put("detail", detail);
        return "FAILED".equals(status) ? 1 : 0;
    }
}
