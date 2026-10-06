package io.meridian.deposit.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.infrastructure.Rows;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-of-day jobs of the current tenant for one business date (the date being closed), callable over HTTP by
 * business-day-service ({@code POST /v1/deposit-accounts/eod/...}) and by the optional in-process scheduler.
 *
 * <p>Every execution is recorded in {@code deposit_eod_run} (one row per tenant, job and business date). A job that
 * COMPLETED is never executed again for that date: the stored result is returned with {@code replayed=true}. A job
 * that COMPLETED_WITH_ERRORS may be re-run (the underlying operations are idempotent: deterministic ledger keys,
 * status and next-date guards). A RUNNING job is refused with 409 unless it is older than {@link #STALE_HOURS}.
 */
@Component
public class EndOfDayJobs {
    private static final Logger log = LoggerFactory.getLogger(EndOfDayJobs.class);
    private static final int MATURITY_NOTICE_DAYS = 7;
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    /** Jobs in their end-of-day order (DepositEodJob). */
    public enum Job { PRODUCT_CHANGES, INTEREST_ACCRUAL, MATURITY_PROCESSING, STANDING_ORDERS, POT_RULES, SWEEPS, DORMANCY_SCAN }

    private final DepositProperties props;
    private final Rows rows;
    private final AccountSupport accounts;
    private final InterestService interest;
    private final DepositAccountService deposits;
    private final TermDepositService termDeposits;
    private final StandingOrderService standingOrders;
    private final SavingsPotService pots;
    private final SweepService sweeps;

    public EndOfDayJobs(DepositProperties props, Rows rows, AccountSupport accounts, InterestService interest,
                        DepositAccountService deposits, TermDepositService termDeposits,
                        StandingOrderService standingOrders, SavingsPotService pots, SweepService sweeps) {
        this.props = props;
        this.rows = rows;
        this.accounts = accounts;
        this.interest = interest;
        this.deposits = deposits;
        this.termDeposits = termDeposits;
        this.standingOrders = standingOrders;
        this.pots = pots;
        this.sweeps = sweeps;
    }

    /** Fallback when business-day-service does not orchestrate: closes yesterday for the configured tenants. */
    @Scheduled(cron = "${meridian.deposit.batch.cron:0 30 0 * * *}", zone = "UTC")
    public void scheduled() {
        if (!props.batch().enabled()) {
            return;
        }
        for (UUID tenant : props.batch().tenants()) {
            try {
                TenantContext.callAs(tenant, "system:end-of-day", () -> runAll(accounts.today().minusDays(1)));
            } catch (Exception e) {
                log.error("end-of-day run failed for tenant {}", tenant, e);
            }
        }
    }

    /** Runs every job for the current tenant and business date in order; returns the result per job. */
    public Map<Job, ObjectNode> runAll(LocalDate businessDate) {
        Map<Job, ObjectNode> out = new LinkedHashMap<>();
        for (Job job : Job.values()) {
            try {
                out.put(job, run(job, businessDate));
            } catch (ApiException e) {
                log.warn("end-of-day job {} for {} not run: {}", job, businessDate, e.getMessage());
            }
        }
        return out;
    }

    /** Runs one job for the current tenant and business date (idempotent per business date). */
    public ObjectNode run(Job job, LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<ObjectNode> claimed = claim(job, businessDate);
        if (claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("""
                    select row_to_json(r)::text from deposit_eod_run r where job = ? and business_date = ?""",
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
            log.error("end-of-day job {} for {} failed", job, businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = rows.queryOne("""
                update deposit_eod_run set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(deposit_eod_run)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow();
        return response(done, false);
    }

    private void execute(Job job, LocalDate date, Map<String, Integer> counts, List<String> errors) {
        switch (job) {
            case PRODUCT_CHANGES -> counts.put("product_changes", deposits.applyScheduledProductChanges(date));
            case INTEREST_ACCRUAL -> accrue(date, counts, errors);
            case MATURITY_PROCESSING -> {
                counts.put("maturing_notices", guarded("maturing notices", errors,
                        () -> termDeposits.notifyMaturing(date, MATURITY_NOTICE_DAYS)));
                counts.put("maturities", guarded("maturities", errors, () -> termDeposits.processMaturities(date).size()));
            }
            case STANDING_ORDERS -> {
                List<ObjectNode> executions = standingOrders.executeDue(date);
                counts.put("executions", executions.size());
                counts.put("succeeded", (int) executions.stream()
                        .filter(e -> "SUCCEEDED".equals(Rows.str(e, "status"))).count());
                counts.put("failed", (int) executions.stream()
                        .filter(e -> !"SUCCEEDED".equals(Rows.str(e, "status"))).count());
            }
            case POT_RULES -> counts.put("pot_rule_runs", pots.runScheduledRules(date));
            case SWEEPS -> counts.put("sweeps", sweeps.runDue(date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
            case DORMANCY_SCAN -> counts.put("accounts_changed", deposits.runDormancy(date));
        }
    }

    /**
     * Accrues every open account through {@code date} (one transaction per account) and capitalises the accounts
     * whose capitalisation falls on the next day (interest accrued through {@code date} is posted).
     */
    private void accrue(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        LocalDate capitalisationDay = date.plusDays(1);
        int accrued = 0;
        int capitalised = 0;
        List<UUID> ids = rows.query("""
                select row_to_json(a)::text from deposit_account a where ledger_account_id is not null
                  and status not in ('CLOSED', 'REJECTED', 'PENDING_APPROVAL', 'PENDING_FUNDING') order by id""")
                .stream().map(a -> Rows.uuid(a, "id")).toList();
        for (UUID id : ids) {
            accrued += guarded("accrual " + id, errors, () -> interest.accrueThrough(id, date));
            boolean due = rows.scalar("select next_capitalisation_date <= ? from deposit_account where id = ?",
                    Boolean.class, capitalisationDay, id).orElse(false);
            if (due) {
                capitalised += guarded("capitalisation " + id, errors,
                        () -> interest.capitalise(id, false, capitalisationDay).capitalisations().size());
            }
        }
        counts.put("accounts", ids.size());
        counts.put("accrual_rows", accrued);
        counts.put("capitalisations", capitalised);
    }

    /**
     * Atomically creates the run row, or takes over a re-runnable one (COMPLETED_WITH_ERRORS, or RUNNING but stale).
     * Empty when another execution owns the date or it already COMPLETED.
     */
    private Optional<ObjectNode> claim(Job job, LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into deposit_eod_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
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

    private static int guarded(String what, List<String> errors, java.util.function.IntSupplier job) {
        try {
            return job.getAsInt();
        } catch (RuntimeException e) {
            log.error("end-of-day step '{}' failed", what, e);
            errors.add(what + ": " + describe(e));
            return 0;
        }
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }
}
