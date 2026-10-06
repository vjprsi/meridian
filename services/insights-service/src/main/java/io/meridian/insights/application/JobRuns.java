package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.insights.infrastructure.Rows;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Batch jobs of the current tenant for one business date, callable by business-day-service. Every execution is
 * recorded in {@code insights_job_run} (one row per tenant, job and business date): a COMPLETED job is replayed with
 * {@code replayed=true}; COMPLETED_WITH_ERRORS may be re-run (each step is idempotent); a RUNNING job is refused with
 * 409 unless stale. Each customer is processed in its own transaction so one failure does not undo the others.
 */
@Component
public class JobRuns {
    private static final Logger log = LoggerFactory.getLogger(JobRuns.class);
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    public enum Job { RECURRING_DETECTION, INSIGHT_GENERATION, BUDGET_ROLLOVER, INSIGHT_EXPIRY }

    private final Rows rows;
    private final TransactionTemplate tx;
    private final RecurringService recurring;
    private final InsightService insights;
    private final BudgetService budgets;

    public JobRuns(Rows rows, TransactionTemplate tx, RecurringService recurring, InsightService insights, BudgetService budgets) {
        this.rows = rows;
        this.tx = tx;
        this.recurring = recurring;
        this.insights = insights;
        this.budgets = budgets;
    }

    public ObjectNode run(Job job, LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<ObjectNode> claimed = tx.execute(s -> claim(job, businessDate));
        if (claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("""
                    select row_to_json(r)::text from insights_job_run r where r.job = ? and r.business_date = ?""",
                    job.name(), businessDate).orElseThrow();
            if ("RUNNING".equals(text(existing, "status"))) {
                throw ApiException.conflict("job_running", job + " for " + businessDate + " is already running");
            }
            return response(existing, true);
        }
        UUID runId = Json.uuid(claimed.get(), "id");
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            execute(job, businessDate, counts, errors);
        } catch (RuntimeException e) {
            log.error("insights job {} for {} failed", job, businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = tx.execute(s -> rows.queryOne("""
                update insights_job_run set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(insights_job_run)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow());
        return response(done, false);
    }

    private void execute(Job job, LocalDate date, Map<String, Integer> counts, List<String> errors) {
        switch (job) {
            case RECURRING_DETECTION -> {
                List<UUID> customers = customers(400);
                int detected = 0;
                for (UUID c : customers) {
                    detected += guarded("detection " + c, errors,
                            () -> recurring.detect(c, null).path("detected_new").asInt(0));
                }
                counts.put("customers", customers.size());
                counts.put("detected_new", detected);
                counts.put("retired", guarded("retire", errors, () -> recurring.retireStale(date)));
            }
            case INSIGHT_GENERATION -> {
                List<UUID> customers = customers(31);
                int generated = 0;
                for (UUID c : customers) {
                    generated += guarded("insights " + c, errors, () -> insights.generate(c, null).path("generated").asInt(0));
                }
                counts.put("customers", customers.size());
                counts.put("generated", generated);
            }
            case BUDGET_ROLLOVER -> counts.put("periods_opened", guarded("rollover", errors, () -> budgets.rollover(date)));
            case INSIGHT_EXPIRY -> counts.put("expired", guarded("expiry", errors, () -> insights.expire(date)));
        }
    }

    private List<UUID> customers(int days) {
        return rows.jdbc().sql("""
                select distinct customer_id from transaction_enrichment where transaction_at > now() - make_interval(days => ?)
                order by customer_id""").param(days).query(UUID.class).list();
    }

    private Optional<ObjectNode> claim(Job job, LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into insights_job_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
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
        out.put("run_id", text(run, "id"));
        out.put("job", text(run, "job"));
        out.put("business_date", text(run, "business_date"));
        out.put("status", text(run, "status"));
        out.put("attempts", run.path("attempts").asInt(1));
        out.put("replayed", replayed);
        out.put("started_at", text(run, "started_at"));
        out.put("completed_at", text(run, "completed_at"));
        if (run.path("result").isObject()) {
            out.setAll((ObjectNode) run.get("result"));
        }
        return out;
    }

    private static int guarded(String what, List<String> errors, IntSupplier step) {
        try {
            return step.getAsInt();
        } catch (RuntimeException e) {
            log.error("insights job step '{}' failed", what, e);
            errors.add(what + ": " + describe(e));
            return 0;
        }
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }
}
