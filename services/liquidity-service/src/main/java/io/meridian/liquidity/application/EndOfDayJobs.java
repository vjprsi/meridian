package io.meridian.liquidity.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.liquidity.infrastructure.Rows;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-of-day liquidity jobs of the current tenant for one business date, called by business-day-service
 * ({@code POST /v1/treasury/liquidity/eod-runs/...}). Each execution is recorded in {@code liquidity_eod_run} (one row
 * per tenant, job and business date): a COMPLETED job is replayed (result returned with {@code replayed=true}), a
 * COMPLETED_WITH_ERRORS job may be re-run, a RUNNING one is refused with 409 unless stale.
 */
@Component
public class EndOfDayJobs {
    private static final Logger log = LoggerFactory.getLogger(EndOfDayJobs.class);
    private static final int STALE_HOURS = 2;

    public enum Job { LIQUIDITY_POSITION_SNAPSHOT }

    private final Rows rows;
    private final PositionService positions;
    private final Events events;
    private final TransactionTemplate tx;

    public EndOfDayJobs(Rows rows, PositionService positions, Events events, TransactionTemplate tx) {
        this.rows = rows;
        this.positions = positions;
        this.events = events;
        this.tx = tx;
    }

    /** Not one transaction: the job runs in its own transaction(s) so a failure is recorded on the run row. */
    public ObjectNode run(Job job, LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<ObjectNode> claimed = claim(job, businessDate);
        if (claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("""
                    select row_to_json(r)::text from liquidity_eod_run r where job = ? and business_date = ?""",
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
            switch (job) {
                case LIQUIDITY_POSITION_SNAPSHOT -> counts.putAll(positions.snapshot(businessDate));
            }
        } catch (RuntimeException e) {
            log.error("end-of-day job {} for {} failed", job, businessDate, e);
            errors.add(e instanceof ApiException a ? a.code() + ": " + a.getMessage() : e.getClass().getSimpleName());
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = tx.execute(st -> {
            ObjectNode d = rows.queryOne("""
                update liquidity_eod_run set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(liquidity_eod_run)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow();
            events.publish("treasury.liquidity_eod_run.completed", "liquidity_eod_run", d);
            return d;
        });
        return response(done, false);
    }

    private Optional<ObjectNode> claim(Job job, LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into liquidity_eod_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
                                                    created_by, updated_by)
                values (?, ?, ?, ?, 'RUNNING', 1, now(), ?, ?)
                on conflict (tenant_id, job, business_date) do update
                  set status = 'RUNNING', attempts = r.attempts + 1, started_at = now(), completed_at = null,
                      result = null, updated_by = excluded.updated_by
                  where r.status = 'COMPLETED_WITH_ERRORS'
                     or (r.status = 'RUNNING' and r.started_at < now() - make_interval(hours => ?))
                returning row_to_json(r)::text""",
                Ids.newId(), TenantContext.require(), job.name(), date, principal, principal, STALE_HOURS);
    }

    private ObjectNode response(ObjectNode run, boolean replayed) {
        ObjectNode out = rows.object();
        out.put("job", Rows.str(run, "job"));
        out.put("business_date", Rows.str(run, "business_date"));
        out.put("run_id", Rows.str(run, "id"));
        out.put("status", Rows.str(run, "status"));
        out.put("attempts", run.get("attempts").asInt());
        out.put("replayed", replayed);
        out.set("result", run.get("result"));
        return out;
    }
}
