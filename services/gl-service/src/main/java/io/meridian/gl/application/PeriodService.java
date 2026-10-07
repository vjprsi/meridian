package io.meridian.gl.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.gl.domain.JournalLine;
import io.meridian.gl.domain.PeriodCalendar;
import io.meridian.gl.domain.PeriodLifecycle;
import io.meridian.gl.domain.PeriodStatus;
import io.meridian.gl.infrastructure.GlStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Accounting periods and the close: create (non-overlapping per legal entity and type), close as maker (runs the
 * checklist in a period_close_run, including the year-end transfer of the result to retained earnings; the period
 * becomes SOFT_CLOSED only when no blocking step fails), approve the close as a different principal (CLOSED),
 * reopen with a reason.
 */
@Service
public class PeriodService {
    private static final String T = "accounting_period";

    private final GlStore store;
    private final CrudEngine crud;
    private final JournalService journals;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Approvals approvals;

    /** Approval resource type for accounting period closes in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "accounting_period_close";

    public PeriodService(GlStore store, CrudEngine crud, JournalService journals, OutboxWriter outbox, JsonMapper json,
                         Approvals approvals) {
        this.store = store;
        this.crud = crud;
        this.journals = journals;
        this.outbox = outbox;
        this.json = json;
        this.approvals = approvals;
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
        OffsetDateTime startedAt = io.meridian.common.time.Clocks.now();

        ArrayNode checklist = json.createArrayNode();
        int failed = 0;
        int warnings = 0;

        long unapproved = store.count("""
                select count(*) from gl_journal j where j.legal_entity_id = ? and j.value_date between ? and ?
                  and j.journal_type not in ('AUTO_AGGREGATE', 'YEAR_END_CLOSE')
                  and not exists (select 1 from gl_journal_approval a where a.gl_journal_id = j.id)
                  and not exists (select 1 from gl_journal_rejection r where r.gl_journal_id = j.id)""", le, start, end);
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
        if (failed == 0) {
            failed += transferResultToRetainedEarnings(checklist, period, le);
        } else {
            step(checklist, "pnl_to_retained_earnings", "SKIPPED", "Not run: earlier blocking steps failed");
        }

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
        run.put("finished_at", io.meridian.common.time.Clocks.now().toString());
        run.set("steps", steps);
        if (failed > 0) {
            run.put("error", failed + " blocking checklist step(s) failed; the period stays " + from);
        }
        String runRow = crud.insert("period_close_run", run);

        if (failed == 0) {
            store.update("update accounting_period set status = ?, closed_by = ?, closed_at = now(), approved_by = null, "
                    + "updated_by = ? where id = ?", to.name(), TenantContext.principal(), TenantContext.principal(), id);
            // Central maker-checker: the checker decides in the workflow inbox; AccountingPeriodCloseApprovalHandler
            // applies it. The close run id is the fingerprint (a reopen + new close makes the request stale).
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("legal_entity_id", le.toString());
            payload.put("period_type", period.get("period_type").asString());
            payload.put("start_date", start.toString());
            payload.put("end_date", end.toString());
            payload.put("close_run_id", runId.toString());
            payload.put("warnings", warnings);
            payload.put("checklist", checklist);
            approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve_close",
                    "Close " + period.get("period_type").asString() + " period " + start + ".." + end, payload),
                    "close:" + id + ":" + runId);
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
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return row.toString();
    }

    /** True while the period is SOFT_CLOSED by the close run of the approval request (fingerprint) awaiting its checker. */
    @Transactional(readOnly = true)
    public boolean awaitsCloseApproval(UUID id, String closeRunId) {
        return store.find(T, id)
                .filter(p -> PeriodStatus.SOFT_CLOSED.name().equals(p.path("status").asString()))
                .filter(p -> closeRunId == null || store.row("""
                        select row_to_json(r)::text from period_close_run r where r.accounting_period_id = ?
                        order by r.id desc limit 1""", id)
                        .filter(r -> closeRunId.equals(r.path("id").asString())).isPresent())
                .isPresent();
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
        if (PeriodStatus.SOFT_CLOSED.name().equals(period.get("status").asString())) {
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "reopened directly");
        }
        return row.toString();
    }

    /**
     * Trial balance of a period: every GL balance with its account code, name and class, debit/credit totals per
     * currency and whether debits equal credits in every currency.
     */
    @Transactional(readOnly = true)
    public JsonNode trialBalance(UUID periodId) {
        JsonNode period = store.require(T, periodId);
        ObjectNode out = json.createObjectNode();
        out.put("accounting_period_id", periodId.toString());
        out.put("legal_entity_id", period.get("legal_entity_id").asString());
        out.put("period_type", period.get("period_type").asString());
        out.put("start_date", period.get("start_date").asString());
        out.put("end_date", period.get("end_date").asString());
        out.put("status", period.get("status").asString());
        boolean balanced = true;
        ArrayNode currencies = out.putArray("currencies");
        for (JsonNode c : store.rows("""
                select json_build_object('currency', currency, 'debits', sum(debits), 'credits', sum(credits))::text
                from gl_balance where accounting_period_id = ? group by currency order by currency""", periodId)) {
            BigDecimal d = new BigDecimal(c.get("debits").asString());
            BigDecimal cr = new BigDecimal(c.get("credits").asString());
            ObjectNode row = currencies.addObject();
            row.put("currency", c.get("currency").asString().trim());
            row.put("debits", d.toPlainString());
            row.put("credits", cr.toPlainString());
            row.put("balanced", d.compareTo(cr) == 0);
            balanced &= d.compareTo(cr) == 0;
        }
        out.put("balanced", balanced);
        ArrayNode lines = out.putArray("lines");
        store.rows("""
                select (row_to_json(b)::jsonb || jsonb_build_object('gl_account_code', a.code, 'gl_account_name', a.name,
                        'account_class', a.account_class))::text
                from gl_balance b join gl_account a on a.id = b.gl_account_id
                where b.accounting_period_id = ? order by a.code, b.currency""", periodId).forEach(lines::add);
        return out;
    }

    /**
     * Year-end close step: moves the year's net profit or loss out of the INCOME and EXPENSE accounts into the
     * legal entity's designated retained-earnings account with a YEAR_END_CLOSE journal on the YEAR period
     * (finer periods are untouched). Retained earnings is EQUITY, so the result rolls into next year's opening.
     * Returns 1 when the step blocks the close.
     */
    private int transferResultToRetainedEarnings(ArrayNode checklist, JsonNode period, UUID le) {
        final String name = "pnl_to_retained_earnings";
        if (!"YEAR".equals(period.get("period_type").asString())) {
            step(checklist, name, "SKIPPED", "Only the YEAR close transfers the result to retained earnings");
            return 0;
        }
        List<JsonNode> pnl = store.rows("""
                select json_build_object('gl_account_id', b.gl_account_id, 'currency', b.currency,
                       'closing_balance', b.closing_balance, 'base_closing_balance', b.base_closing_balance)::text
                from gl_balance b join gl_account a on a.id = b.gl_account_id
                where b.accounting_period_id = ?::uuid and a.account_class in ('INCOME', 'EXPENSE')
                  and b.closing_balance <> 0
                order by a.code, b.currency""", period.get("id").asString());
        if (pnl.isEmpty()) {
            step(checklist, name, "PASSED", "No profit or loss to transfer");
            return 0;
        }
        var re = store.row("""
                select row_to_json(a)::text from gl_account a
                where a.legal_entity_id = ? and a.is_retained_earnings and a.status = 'ACTIVE'""", le);
        if (re.isEmpty()) {
            return step(checklist, name, "FAILED",
                    "No ACTIVE retained-earnings GL account is designated for the legal entity (is_retained_earnings)");
        }
        UUID reId = UUID.fromString(re.get().get("id").asString());
        List<JournalLine> lines = new ArrayList<>();
        Map<String, BigDecimal[]> net = new TreeMap<>();
        for (JsonNode b : pnl) {
            BigDecimal closing = new BigDecimal(b.get("closing_balance").asString());
            BigDecimal closingBase = new BigDecimal(b.get("base_closing_balance").asString());
            String ccy = b.get("currency").asString().trim();
            // Debit-positive closing balance: a debit balance (loss side) is cleared with a credit, and vice versa.
            lines.add(new JournalLine(UUID.fromString(b.get("gl_account_id").asString()),
                    closing.signum() > 0 ? JournalLine.Direction.CREDIT : JournalLine.Direction.DEBIT,
                    closing.abs(), ccy, closingBase.abs(), null, null, null));
            BigDecimal[] n = net.computeIfAbsent(ccy, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            n[0] = n[0].add(closing);
            n[1] = n[1].add(closingBase);
        }
        StringBuilder result = new StringBuilder();
        net.forEach((ccy, n) -> {
            if (n[0].signum() != 0) {
                lines.add(new JournalLine(reId, n[0].signum() > 0 ? JournalLine.Direction.DEBIT : JournalLine.Direction.CREDIT,
                        n[0].abs(), ccy, n[1].abs(), null, null, null));
            }
            result.append(result.isEmpty() ? "" : ", ").append(n[0].signum() > 0 ? "loss " : "profit ")
                    .append(n[0].abs().toPlainString()).append(' ').append(ccy);
        });
        String j = journals.postClosingJournal(le, period, LocalDate.parse(period.get("end_date").asString()),
                "Year-end close " + period.get("start_date").asString() + ".." + period.get("end_date").asString()
                        + ": result to retained earnings", lines);
        step(checklist, name, "PASSED", "Transferred " + result + " to " + re.get().get("code").asString()
                + " (journal " + json.readTree(j).get("id").asString() + ")");
        return 0;
    }

    private int step(ArrayNode checklist, String name, String status, String detail) {
        ObjectNode s = checklist.addObject();
        s.put("step", name);
        s.put("status", status);
        s.put("detail", detail);
        return "FAILED".equals(status) ? 1 : 0;
    }
}
