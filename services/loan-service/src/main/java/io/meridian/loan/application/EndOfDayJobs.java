package io.meridian.loan.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loan.config.LoanProperties;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-of-day jobs of the current tenant for one business date, called over HTTP by business-day-service
 * ({@code POST /v1/loans/eod/...}, {@code POST /v1/credit-lines/eod/statements}) or the optional in-process scheduler.
 *
 * <p>Every execution is recorded in {@code loan_eod_run} (one row per tenant, job and business date). A COMPLETED job
 * is never executed again for that date: the stored result is returned with {@code replayed=true}. A job that
 * COMPLETED_WITH_ERRORS may be re-run (each item is idempotent: deterministic ledger keys, accrual dates, status
 * guards). A RUNNING job is refused with 409 unless it is older than {@link #STALE_HOURS}. Items run in their own
 * transactions so one failing loan does not stop the batch.
 */
@Component
public class EndOfDayJobs {
    private static final Logger log = LoggerFactory.getLogger(EndOfDayJobs.class);
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    /** Jobs in their end-of-day order (LendingEodJob values owned by loan-service). */
    public enum Job { DAILY_ACCRUAL, INSTALLMENT_DUE, AUTO_DEBIT, DELINQUENCY_UPDATE, CREDIT_LINE_STATEMENTS }

    private final LoanProperties props;
    private final Rows rows;
    private final LoanSupport loans;
    private final ServicingJobs servicing;
    private final DisbursementService disbursements;
    private final PrepaymentService prepayments;
    private final CreditLineService lines;

    public EndOfDayJobs(LoanProperties props, LoanSupport loans, ServicingJobs servicing, DisbursementService disbursements,
                        PrepaymentService prepayments, CreditLineService lines) {
        this.props = props;
        this.rows = loans.rows();
        this.loans = loans;
        this.servicing = servicing;
        this.disbursements = disbursements;
        this.prepayments = prepayments;
        this.lines = lines;
    }

    /** Fallback when business-day-service does not orchestrate: closes yesterday for the configured tenants. */
    @Scheduled(cron = "${meridian.loan.batch.cron:0 45 0 * * *}", zone = "UTC")
    public void scheduled() {
        if (!props.batch().enabled()) {
            return;
        }
        for (UUID tenant : props.batch().tenants()) {
            try {
                TenantContext.callAs(tenant, "system:end-of-day", () -> runAll(loans.today().minusDays(1)));
            } catch (Exception e) {
                log.error("loan end-of-day run failed for tenant {}", tenant, e);
            }
        }
    }

    public Map<Job, ObjectNode> runAll(LocalDate businessDate) {
        Map<Job, ObjectNode> out = new LinkedHashMap<>();
        for (Job job : Job.values()) {
            try {
                out.put(job, run(job, businessDate));
            } catch (ApiException e) {
                log.warn("loan end-of-day job {} for {} not run: {}", job, businessDate, e.getMessage());
            }
        }
        return out;
    }

    public ObjectNode run(Job job, LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<ObjectNode> claimed = claim(job, businessDate);
        if (claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("select row_to_json(r)::text from loan_eod_run r where job = ? and business_date = ?",
                    job.name(), businessDate).orElseThrow();
            if ("RUNNING".equals(Rows.str(existing, "status"))) {
                throw ApiException.conflict("eod_job_running", job + " for " + businessDate + " is already running");
            }
            return response(existing, true);
        }
        UUID runId = Rows.uuid(claimed.get(), "id");
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            execute(job, businessDate, counts, errors);
        } catch (RuntimeException e) {
            log.error("loan end-of-day job {} for {} failed", job, businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = rows.queryOne("""
                update loan_eod_run set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(loan_eod_run)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow();
        return response(done, false);
    }

    private void execute(Job job, LocalDate date, Map<String, Integer> counts, List<String> errors) {
        switch (job) {
            case DAILY_ACCRUAL -> {
                int executed = 0;
                for (UUID d : disbursements.scheduledDue(date)) {
                    executed += guarded("scheduled disbursement " + d, errors, () -> {
                        disbursements.retry(d);
                        return 1;
                    });
                }
                counts.put("scheduled_disbursements", executed);
                counts.put("quotes_expired", guarded("quote expiry", errors, prepayments::expireQuotes));
                List<UUID> ids = servicing.serviceableLoans();
                int days = 0;
                for (UUID id : ids) {
                    days += guarded("accrual " + id, errors, () -> servicing.accrue(id, date));
                }
                counts.put("loans", ids.size());
                counts.put("accrual_rows", days);
            }
            case INSTALLMENT_DUE -> {
                int due = 0;
                int overdue = 0;
                int fees = 0;
                int credits = 0;
                for (UUID id : servicing.serviceableLoans()) {
                    ServicingJobs.DueResult[] r = new ServicingJobs.DueResult[1];
                    guarded("due processing " + id, errors, () -> {
                        r[0] = servicing.processDue(id, date);
                        return 1;
                    });
                    if (r[0] != null) {
                        due += r[0].due();
                        overdue += r[0].overdue();
                        fees += r[0].lateFees();
                        credits += r[0].creditApplied().signum() > 0 ? 1 : 0;
                    }
                }
                counts.put("installments_due", due);
                counts.put("installments_overdue", overdue);
                counts.put("late_fees", fees);
                counts.put("credit_balances_applied", credits);
            }
            case AUTO_DEBIT -> {
                Map<ServicingJobs.DebitOutcome, Integer> outcomes = new LinkedHashMap<>();
                for (UUID id : servicing.serviceableLoans()) {
                    ServicingJobs.DebitOutcome[] o = new ServicingJobs.DebitOutcome[1];
                    guarded("auto-debit " + id, errors, () -> {
                        o[0] = servicing.autoDebit(id, date);
                        return 1;
                    });
                    if (o[0] != null) {
                        outcomes.merge(o[0], 1, Integer::sum);
                    }
                }
                counts.put("collected", outcomes.getOrDefault(ServicingJobs.DebitOutcome.COLLECTED, 0));
                counts.put("partial", outcomes.getOrDefault(ServicingJobs.DebitOutcome.PARTIAL, 0));
                counts.put("insufficient_funds", outcomes.getOrDefault(ServicingJobs.DebitOutcome.INSUFFICIENT_FUNDS, 0));
                counts.put("nothing_due", outcomes.getOrDefault(ServicingJobs.DebitOutcome.NOTHING_DUE, 0));
                int autopays = 0;
                for (UUID id : lines.autopayCandidates()) {
                    autopays += guarded("credit line autopay " + id, errors,
                            () -> lines.autopay(id, date).signum() > 0 ? 1 : 0);
                }
                counts.put("credit_line_autopays", autopays);
            }
            case DELINQUENCY_UPDATE -> {
                int changed = 0;
                List<UUID> ids = servicing.serviceableLoans();
                for (UUID id : ids) {
                    changed += guarded("delinquency " + id, errors, () -> servicing.delinquency(id, date) ? 1 : 0);
                }
                counts.put("loans", ids.size());
                counts.put("loans_changed", changed);
                int lineChanges = 0;
                for (UUID id : lines.statementCandidates()) {
                    lineChanges += guarded("credit line delinquency " + id, errors, () -> lines.delinquency(id, date) ? 1 : 0);
                }
                counts.put("credit_lines_changed", lineChanges);
            }
            case CREDIT_LINE_STATEMENTS -> {
                int issued = 0;
                List<UUID> ids = lines.statementCandidates();
                for (UUID id : ids) {
                    issued += guarded("statement " + id, errors, () -> lines.statement(id, date).isPresent() ? 1 : 0);
                    guarded("available " + id, errors, () -> {
                        lines.refreshAvailable(id);
                        return 1;
                    });
                }
                counts.put("credit_lines", ids.size());
                counts.put("statements_issued", issued);
            }
        }
    }

    private Optional<ObjectNode> claim(Job job, LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into loan_eod_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
                                               created_by, updated_by)
                values (?, ?, ?, ?, 'RUNNING', 1, now(), ?, ?)
                on conflict (tenant_id, job, business_date) do update
                  set status = 'RUNNING', attempts = r.attempts + 1, started_at = now(), completed_at = null,
                      result = null, updated_at = now(), updated_by = excluded.updated_by
                  where r.status = 'COMPLETED_WITH_ERRORS'
                     or (r.status = 'RUNNING' and r.started_at < now() - make_interval(hours => ?))
                returning row_to_json(r)::text""",
                Ids.newId(), TenantContext.require(), job.name(), date, principal, principal, STALE_HOURS);
    }

    private ObjectNode response(ObjectNode run, boolean replayed) {
        ObjectNode out = rows.object();
        out.put("run_id", Rows.str(run, "id"));
        out.put("job", Rows.str(run, "job"));
        out.put("business_date", Rows.str(run, "business_date"));
        out.put("status", Rows.str(run, "status"));
        out.put("attempts", run.path("attempts").asInt(1));
        out.put("replayed", replayed);
        out.put("started_at", Rows.str(run, "started_at"));
        out.put("completed_at", Rows.str(run, "completed_at"));
        if (run.path("result").isObject()) {
            out.setAll((ObjectNode) run.get("result"));
        }
        return out;
    }

    private static int guarded(String what, List<String> errors, IntSupplier step) {
        try {
            return step.getAsInt();
        } catch (RuntimeException e) {
            log.error("loan end-of-day step '{}' failed", what, e);
            errors.add(what + ": " + describe(e));
            return 0;
        }
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}
