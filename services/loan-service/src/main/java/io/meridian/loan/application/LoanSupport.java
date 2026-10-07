package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.dec;
import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loan.config.LoanProperties;
import io.meridian.loan.domain.BusinessCalendar;
import io.meridian.loan.domain.Currencies;
import io.meridian.loan.domain.DayCount;
import io.meridian.loan.domain.Delinquency;
import io.meridian.loan.domain.Frequency;
import io.meridian.loan.domain.Lifecycles;
import io.meridian.loan.domain.ScheduleGenerator;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared loan mechanics: row access, schedule persistence and regeneration, derived balances, delinquency (DPD,
 * bucket, arrears status, ECL stage hint) with status history, and the loan's receivable account in the ledger.
 */
@Component
public class LoanSupport {
    public static final String LOAN = "loan";
    public static final String INSTALLMENT = "schedule_installment";
    public static final Set<String> OPEN_INSTALLMENT = Set.of("SCHEDULED", "DUE", "PARTIALLY_PAID", "OVERDUE");
    public static final Set<String> SERVICEABLE = Set.of("ACTIVE", "IN_ARREARS", "RESTRUCTURED");
    static final String OPEN_SQL = "('SCHEDULED','DUE','PARTIALLY_PAID','OVERDUE')";

    private final Rows rows;
    private final Clock clock;
    private final Events events;
    private final LoanProperties props;
    private final BusinessCalendar calendar;
    private final LedgerPort ledger;
    private final io.meridian.web.time.BusinessDates businessDates;

    public LoanSupport(Rows rows, Clock clock, Events events, LoanProperties props, BusinessCalendar calendar,
                       LedgerPort ledger, io.meridian.web.time.BusinessDates businessDates) {
        this.rows = rows;
        this.businessDates = businessDates;
        this.clock = clock;
        this.events = events;
        this.props = props;
        this.calendar = calendar;
        this.ledger = ledger;
    }

    public Rows rows() {
        return rows;
    }

    public LoanProperties props() {
        return props;
    }

    public LoanProperties.Gl gl() {
        return props.gl();
    }

    public LedgerPort ledger() {
        return ledger;
    }

    public Events events() {
        return events;
    }

    public BusinessCalendar calendar() {
        return calendar;
    }

    public ObjectNode lock(UUID id) {
        return rows.lock(LOAN, id);
    }

    public ObjectNode get(UUID id) {
        return rows.get(LOAN, id);
    }

    /**
     * The tenant's business date (business-day-service), else this service's clock date. Prefer {@link #today(JsonNode)}
     * when a loan is at hand: its legal entity may be on another business day.
     */
    public LocalDate today() {
        return businessDates.today(() -> LocalDate.now(clock));
    }

    /** The business date of the loan's (or loan request's) legal entity, else the tenant's, else the clock date. */
    public LocalDate today(JsonNode loan) {
        UUID legalEntity = loan == null ? null : uuid(loan, "legal_entity_id");
        return legalEntity == null ? today() : businessDates.today(legalEntity, () -> LocalDate.now(clock));
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    public static int scale(JsonNode loan) {
        return Currencies.digits(str(loan, "currency"));
    }

    public static BigDecimal round(BigDecimal v, JsonNode loan) {
        return v.setScale(scale(loan), RoundingMode.HALF_EVEN);
    }

    public static void requireStatus(JsonNode loan, Set<String> allowed, String action) {
        String s = str(loan, "status");
        if (!allowed.contains(s)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action + " a loan in status " + s);
        }
    }

    // ------------------------------------------------------------------ ledger account

    /** The loan's receivable account, opened on first use (idempotent per loan). */
    public UUID receivable(ObjectNode loan) {
        UUID existing = uuid(loan, "ledger_account_id");
        if (existing != null) {
            return existing;
        }
        UUID id = uuid(loan, "id");
        UUID acc = ledger.openReceivable(LedgerPort.key("loan-receivable", id, null), "LN-" + str(loan, "loan_number"),
                "Loan receivable " + str(loan, "loan_number"), str(loan, "currency"), "LOAN", id);
        ObjectNode upd = rows.object();
        upd.put("ledger_account_id", acc.toString());
        loan.setAll(rows.update(LOAN, id, upd));
        return acc;
    }

    // ------------------------------------------------------------------ instalments

    public List<ObjectNode> openInstallments(UUID loanId) {
        return rows.query("select row_to_json(i)::text from schedule_installment i where loan_id = ? and status in "
                + OPEN_SQL + " order by due_date, installment_no", loanId);
    }

    public static BigDecimal remaining(JsonNode inst, String component) {
        return switch (component) {
            case "PRINCIPAL" -> decOr0(inst, "principal_due").subtract(decOr0(inst, "principal_paid"));
            case "INTEREST" -> decOr0(inst, "interest_due").subtract(decOr0(inst, "interest_paid"));
            case "FEE" -> decOr0(inst, "fees_due").subtract(decOr0(inst, "fees_paid"));
            case "PENALTY" -> decOr0(inst, "penalties_due").subtract(decOr0(inst, "penalties_paid"));
            default -> BigDecimal.ZERO;
        };
    }

    public static BigDecimal remainingTotal(JsonNode inst) {
        return remaining(inst, "PRINCIPAL").add(remaining(inst, "INTEREST")).add(remaining(inst, "FEE"))
                .add(remaining(inst, "PENALTY"));
    }

    /** Status of an open instalment after a change of its paid/due amounts, as of {@code asOf}. */
    public static String installmentStatus(JsonNode inst, LocalDate asOf) {
        BigDecimal left = remainingTotal(inst);
        if (left.signum() <= 0) {
            return "PAID";
        }
        LocalDate due = Rows.date(inst, "due_date");
        if (due.isBefore(asOf)) {
            return "OVERDUE";
        }
        boolean anyPaid = decOr0(inst, "principal_paid").add(decOr0(inst, "interest_paid")).add(decOr0(inst, "fees_paid"))
                .add(decOr0(inst, "penalties_paid")).signum() > 0;
        if (anyPaid) {
            return "PARTIALLY_PAID";
        }
        return due.isAfter(asOf) ? "SCHEDULED" : "DUE";
    }

    // ------------------------------------------------------------------ schedules

    /** Generator terms of a loan for {@code principal} over {@code periods} from {@code start}. */
    public ScheduleGenerator.Terms terms(JsonNode loan, BigDecimal principal, BigDecimal rate, int periods,
                                         LocalDate start, LocalDate firstDue, String type) {
        ScheduleGenerator.Type t = ScheduleGenerator.Type.parse(type != null ? type : str(loan, "schedule_type"));
        List<ScheduleGenerator.CustomLine> custom = null;
        if (t == ScheduleGenerator.Type.CUSTOM) {
            custom = new ArrayList<>();
            for (JsonNode c : loan.path("custom_schedule")) {
                LocalDate d = LocalDate.parse(c.path("due_date").asString());
                if (d.isAfter(start)) {
                    custom.add(new ScheduleGenerator.CustomLine(d, new BigDecimal(c.path("principal").asString("0"))));
                }
            }
            BigDecimal sum = custom.stream().map(ScheduleGenerator.CustomLine::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!custom.isEmpty() && sum.compareTo(principal) != 0) {
                // regenerated custom schedules keep the dates and rescale the remaining principal proportionally
                List<ScheduleGenerator.CustomLine> scaled = new ArrayList<>();
                BigDecimal acc = BigDecimal.ZERO;
                for (int i = 0; i < custom.size(); i++) {
                    BigDecimal p = i == custom.size() - 1 ? principal.subtract(acc)
                            : sum.signum() == 0 ? BigDecimal.ZERO
                            : principal.multiply(custom.get(i).principal()).divide(sum, scale(loan), RoundingMode.HALF_EVEN);
                    acc = acc.add(p);
                    scaled.add(new ScheduleGenerator.CustomLine(custom.get(i).dueDate(), p));
                }
                custom = scaled;
            }
            if (custom.isEmpty()) {
                t = ScheduleGenerator.Type.BULLET;
            }
        }
        BigDecimal balloon = t == ScheduleGenerator.Type.BALLOON ? dec(loan, "balloon_amount") : null;
        if (balloon != null && balloon.compareTo(principal) >= 0) {
            t = ScheduleGenerator.Type.BULLET;
            balloon = null;
        }
        try {
            return new ScheduleGenerator.Terms(principal, rate, DayCount.parse(str(loan, "day_count")), t,
                    Frequency.parse(str(loan, "repayment_frequency")), periods, start, firstDue, balloon,
                    Rows.integer(loan, "interest_only_periods"), custom, scale(loan), calendar,
                    BusinessCalendar.Convention.parse(str(loan, "business_day_convention")));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_schedule", e.getMessage());
        }
    }

    public ScheduleGenerator.Schedule generate(ScheduleGenerator.Terms terms) {
        try {
            return ScheduleGenerator.generate(terms);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_schedule", e.getMessage());
        }
    }

    /** The schedule as a JSON array (previews). */
    public ArrayNode preview(ScheduleGenerator.Schedule s) {
        ArrayNode out = rows.json().createArrayNode();
        for (ScheduleGenerator.Line l : s.lines()) {
            ObjectNode o = out.addObject();
            o.put("installment_no", l.no());
            o.put("period_start", l.periodStart().toString());
            o.put("due_date", l.dueDate().toString());
            o.put("principal", l.principal().toPlainString());
            o.put("interest", l.interest().toPlainString());
            o.put("total", l.total().toPlainString());
            o.put("balance_after", l.balanceAfter().toPlainString());
        }
        return out;
    }

    /**
     * Persists {@code s} as the loan's new ACTIVE schedule version. The previous active version is SUPERSEDED and its
     * not-yet-due (SCHEDULED) instalments are RESCHEDULED; with {@code replaceOpen} the due, partially paid and overdue
     * ones are RESCHEDULED as well (restructure with capitalisation), otherwise they stay open and payable.
     */
    public ObjectNode persistSchedule(ObjectNode loan, ScheduleGenerator.Schedule s, String reason, LocalDate effective,
                                      BigDecimal rate, boolean replaceOpen) {
        UUID loanId = uuid(loan, "id");
        rows.execute("update repayment_schedule set status = 'SUPERSEDED', updated_by = ? where loan_id = ? and status = 'ACTIVE'",
                io.meridian.common.tenancy.TenantContext.principal(), loanId);
        rows.execute("update schedule_installment set status = 'RESCHEDULED', updated_by = ? where loan_id = ? and status in "
                + (replaceOpen ? OPEN_SQL : "('SCHEDULED')"), io.meridian.common.tenancy.TenantContext.principal(), loanId);
        int version = rows.scalar("select coalesce(max(version), 0) + 1 from repayment_schedule where loan_id = ?",
                Integer.class, loanId).orElse(1);
        ObjectNode sch = rows.object();
        sch.put("loan_id", loanId.toString());
        sch.put("version", version);
        sch.put("schedule_type", str(loan, "schedule_type"));
        sch.put("status", "ACTIVE");
        sch.put("installment_count", s.lines().size());
        sch.put("total_principal", s.totalPrincipal().toPlainString());
        sch.put("total_interest", s.totalInterest().toPlainString());
        sch.put("total_fees", "0");
        sch.put("currency", str(loan, "currency"));
        sch.put("effective_from", effective.toString());
        sch.put("interest_rate", rate.toPlainString());
        sch.put("reason", reason);
        ObjectNode schedule = rows.insert("repayment_schedule", sch);
        LocalDate today = today(loan);
        for (ScheduleGenerator.Line l : s.lines()) {
            ObjectNode i = rows.object();
            i.put("repayment_schedule_id", str(schedule, "id"));
            i.put("loan_id", loanId.toString());
            i.put("installment_no", l.no());
            i.put("period_start", l.periodStart().toString());
            i.put("due_date", l.dueDate().toString());
            i.put("principal_due", l.principal().toPlainString());
            i.put("interest_due", l.interest().toPlainString());
            i.put("fees_due", "0");
            i.put("total_due", l.total().toPlainString());
            i.put("outstanding_balance_after", l.balanceAfter().toPlainString());
            i.put("status", l.dueDate().isAfter(today) ? "SCHEDULED" : "DUE");
            rows.insert(INSTALLMENT, i);
        }
        ObjectNode upd = rows.object();
        upd.put("maturity_date", s.maturity().toString());
        loan.setAll(rows.update(LOAN, loanId, upd));
        refresh(loan);
        return schedule;
    }

    /** Nominal first due date of the schedule cadence on or after {@code onOrAfter} (anchored on the loan's first repayment date). */
    public LocalDate cadenceOnOrAfter(JsonNode loan, LocalDate onOrAfter) {
        Frequency f = Frequency.parse(str(loan, "repayment_frequency"));
        LocalDate anchor = Rows.date(loan, "first_repayment_date");
        if (anchor == null || f == Frequency.AT_MATURITY) {
            return null;
        }
        BusinessCalendar.Convention conv = BusinessCalendar.Convention.parse(str(loan, "business_day_convention"));
        for (int k = 0; k < 100_000; k++) {
            LocalDate nominal = f.nth(anchor, k);
            if (!calendar.adjust(nominal, conv).isBefore(onOrAfter)) {
                return nominal;
            }
        }
        return null;
    }

    /**
     * Regenerates the not-yet-due part of the schedule for the outstanding principal that is not already due
     * (rate change, partial prepayment, disbursement). {@code periods} null keeps the number of future instalments
     * (same maturity); {@code fixedInstalment} keeps the instalment and shortens the term instead.
     */
    public ObjectNode regenerateFuture(ObjectNode loan, String reason, LocalDate effective, BigDecimal rate,
                                       Integer periods, BigDecimal fixedInstalment) {
        UUID loanId = uuid(loan, "id");
        List<ObjectNode> open = openInstallments(loanId);
        BigDecimal dueNotPaid = BigDecimal.ZERO;
        LocalDate lastDue = null;
        LocalDate firstFuture = null;
        int future = 0;
        for (ObjectNode i : open) {
            if ("SCHEDULED".equals(str(i, "status"))) {
                future++;
                if (firstFuture == null) {
                    firstFuture = Rows.date(i, "due_date");
                }
            } else {
                dueNotPaid = dueNotPaid.add(remaining(i, "PRINCIPAL"));
                LocalDate d = Rows.date(i, "due_date");
                lastDue = lastDue == null || d.isAfter(lastDue) ? d : lastDue;
            }
        }
        BigDecimal principal = decOr0(loan, "outstanding_principal").subtract(dueNotPaid);
        if (principal.signum() <= 0) {
            // nothing left to schedule: retire future instalments
            rows.execute("update schedule_installment set status = 'RESCHEDULED' where loan_id = ? and status = 'SCHEDULED'", loanId);
            refresh(loan);
            return null;
        }
        LocalDate prevDue = rows.scalar("""
                select max(due_date) from schedule_installment where loan_id = ? and due_date <= ?
                  and status not in ('RESCHEDULED')""", LocalDate.class, loanId, effective).orElse(null);
        LocalDate start = prevDue != null ? prevDue : Rows.date(loan, "start_date") != null ? Rows.date(loan, "start_date") : effective;
        if (lastDue != null && lastDue.isAfter(start)) {
            start = lastDue;
        }
        LocalDate firstDue = firstFuture != null ? cadenceOnOrAfter(loan, firstFuture) : null;
        if (firstDue == null && firstFuture != null) {
            firstDue = firstFuture;
        }
        int n = periods != null ? periods : Math.max(future, 1);
        Frequency f = Frequency.parse(str(loan, "repayment_frequency"));
        if (fixedInstalment != null && fixedInstalment.signum() > 0) {
            BigDecimal r = rate.divide(BigDecimal.valueOf(f.periodsPerYear()), java.math.MathContext.DECIMAL128);
            try {
                n = Math.min(n, ScheduleGenerator.periodsFor(principal, fixedInstalment, r));
            } catch (IllegalArgumentException e) {
                // instalment no longer covers interest: keep the number of periods
            }
        }
        if (firstDue == null || !firstDue.isAfter(start)) {
            firstDue = f.next(start, Rows.integer(loan, "term_months"));
        }
        String type = str(loan, "schedule_type");
        if ("BULLET".equals(type)) {
            n = 1;
            LocalDate maturity = Rows.date(loan, "maturity_date");
            if (maturity != null && maturity.isAfter(start)) {
                firstDue = maturity;
            }
        }
        ScheduleGenerator.Schedule s = generate(terms(loan, principal, rate, n, start, firstDue, type));
        return persistSchedule(loan, s, reason, effective, rate, false);
    }

    // ------------------------------------------------------------------ balances

    /** Recomputes the derived balances of a loan (total outstanding, overdue amount, next due date) and stores them. */
    public ObjectNode refresh(ObjectNode loan) {
        UUID loanId = uuid(loan, "id");
        LocalDate today = today(loan);
        BigDecimal overdue = BigDecimal.ZERO;
        LocalDate nextDue = null;
        for (ObjectNode i : openInstallments(loanId)) {
            BigDecimal left = remainingTotal(i);
            if (left.signum() <= 0) {
                continue;
            }
            LocalDate d = Rows.date(i, "due_date");
            if (d.isBefore(today)) {
                overdue = overdue.add(left);
            }
            if (nextDue == null) {
                nextDue = d;
            }
        }
        BigDecimal total = decOr0(loan, "outstanding_principal").add(decOr0(loan, "outstanding_interest").max(BigDecimal.ZERO))
                .add(decOr0(loan, "outstanding_fees")).add(decOr0(loan, "outstanding_penalties"));
        ObjectNode upd = rows.object();
        upd.put("total_outstanding", total.toPlainString());
        upd.put("overdue_amount", overdue.toPlainString());
        if (nextDue != null) {
            upd.put("next_due_date", nextDue.toString());
        } else {
            upd.putNull("next_due_date");
        }
        loan.setAll(rows.update(LOAN, loanId, upd));
        return loan;
    }

    /** Adds deltas to the loan's money columns (principal, interest, fees, penalties) and refreshes derived values. */
    public ObjectNode adjust(ObjectNode loan, Map<String, BigDecimal> deltas) {
        ObjectNode upd = rows.object();
        deltas.forEach((col, d) -> upd.put(col, decOr0(loan, col).add(d).toPlainString()));
        loan.setAll(rows.update(LOAN, uuid(loan, "id"), upd));
        return refresh(loan);
    }

    // ------------------------------------------------------------------ status and delinquency

    /**
     * Changes the loan status (validated), records the transition in the status history and publishes
     * {@code lending.loan.status_changed} (payload: the loan after the change).
     */
    public ObjectNode transition(ObjectNode loan, String to, String reason, LocalDate businessDate) {
        String from = str(loan, "status");
        if (from.equals(to)) {
            return loan;
        }
        try {
            Lifecycles.require(Lifecycles.LOAN, "Loan", from, to);
        } catch (Lifecycles.InvalidTransition e) {
            throw ApiException.conflict(e.code(), e.getMessage());
        }
        ObjectNode upd = rows.object();
        upd.put("status", to);
        loan.setAll(rows.update(LOAN, uuid(loan, "id"), upd));
        history(loan, from, reason, businessDate, str(loan, "delinquency_bucket"));
        events.publish("lending.loan.status_changed", LOAN, loan);
        return loan;
    }

    private ObjectNode history(ObjectNode loan, String from, String reason, LocalDate businessDate, String fromBucket) {
        ObjectNode h = rows.object();
        h.put("loan_id", str(loan, "id"));
        if (from != null) {
            h.put("from_status", from);
        }
        h.put("to_status", str(loan, "status"));
        h.put("days_past_due", loan.path("days_past_due").asInt(0));
        if (fromBucket != null) {
            h.put("from_bucket", fromBucket);
        }
        h.put("delinquency_bucket", str(loan, "delinquency_bucket"));
        h.put("ecl_stage", str(loan, "ecl_stage"));
        h.put("overdue_amount", decOr0(loan, "overdue_amount").toPlainString());
        h.put("total_outstanding", decOr0(loan, "total_outstanding").toPlainString());
        h.put("customer_id", str(loan, "customer_id"));
        h.put("currency", str(loan, "currency"));
        h.put("business_date", (businessDate == null ? today(loan) : businessDate).toString());
        if (reason != null) {
            h.put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        }
        return rows.insert("loan_status_history", h);
    }

    /**
     * Recomputes DPD (days since the oldest unpaid due date before {@code asOf}), bucket, ECL stage hint and the
     * arrears status; records history and publishes {@code delinquency_changed} when the bucket or status changed.
     * Returns true on a change.
     */
    public boolean delinquency(ObjectNode loan, LocalDate asOf, String reason) {
        if (!SERVICEABLE.contains(str(loan, "status"))) {
            return false;
        }
        UUID loanId = uuid(loan, "id");
        refresh(loan);
        LocalDate oldest = null;
        for (ObjectNode i : openInstallments(loanId)) {
            LocalDate d = Rows.date(i, "due_date");
            if (d.isBefore(asOf) && remainingTotal(i).signum() > 0) {
                oldest = oldest == null || d.isBefore(oldest) ? d : oldest;
                int late = (int) ChronoUnit.DAYS.between(d, asOf);
                if (late != i.path("days_late").asInt(0)) {
                    ObjectNode u = rows.object();
                    u.put("days_late", late);
                    rows.update(INSTALLMENT, uuid(i, "id"), u);
                }
            }
        }
        int dpd = oldest == null ? 0 : (int) ChronoUnit.DAYS.between(oldest, asOf);
        String bucket = Delinquency.bucket(dpd);
        boolean forborne = Rows.bool(loan, "is_forborne");
        String stage = Delinquency.eclStage(dpd, forborne, false);
        String fromStatus = str(loan, "status");
        String fromBucket = str(loan, "delinquency_bucket");
        int grace = loan.path("grace_period_days").asInt(0);
        String status = fromStatus;
        if (dpd > grace) {
            status = "IN_ARREARS";
        } else if ("IN_ARREARS".equals(fromStatus)) {
            status = forborne ? "RESTRUCTURED" : "ACTIVE";
        }
        ObjectNode upd = rows.object();
        upd.put("days_past_due", dpd);
        upd.put("delinquency_bucket", bucket);
        upd.put("ecl_stage", stage);
        upd.put("status", status);
        loan.setAll(rows.update(LOAN, loanId, upd));
        boolean changed = !status.equals(fromStatus) || !bucket.equals(fromBucket);
        if (changed) {
            ObjectNode h = history(loan, fromStatus, reason, asOf, fromBucket);
            events.publish("lending.loan.delinquency_changed", "loan_status_history", h);
        }
        if (!status.equals(fromStatus)) {
            events.publish("lending.loan.status_changed", LOAN, loan);
        }
        return changed;
    }

    // ------------------------------------------------------------------ misc

    public BigDecimal penaltyRate(JsonNode loan) {
        BigDecimal p = dec(loan, "penalty_rate");
        return p == null ? BigDecimal.ZERO : p;
    }

    public OffsetDateTime dbNow() {
        return rows.scalar("select now()", OffsetDateTime.class).orElseThrow();
    }
}
