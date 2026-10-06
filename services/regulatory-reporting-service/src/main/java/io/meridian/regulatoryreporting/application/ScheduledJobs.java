package io.meridian.regulatoryreporting.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.infrastructure.Store;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-tenant scheduled jobs (called over HTTP by business-day-service with the tenant's credentials), idempotent per
 * business date through {@code report_job_run}: a COMPLETED job is never executed again for that date (the stored
 * result is returned with {@code replayed=true}); COMPLETED_WITH_ERRORS may be re-run; a RUNNING job is refused with
 * 409 unless it is stale.
 */
@Component
public class ScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    /** A job body: fills counts and errors; may throw (recorded as an error). */
    public interface Body {
        void run(Map<String, Integer> counts, List<String> errors);
    }

    private final Store store;

    public ScheduledJobs(Store store) {
        this.store = store;
    }

    public ObjectNode run(String job, LocalDate businessDate, Body body) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<JsonNode> claimed = claim(job, businessDate);
        if (claimed.isEmpty()) {
            JsonNode existing = store.row("select row_to_json(r)::text from report_job_run r where job = ? and business_date = ?",
                    job, businessDate).orElseThrow();
            if ("RUNNING".equals(existing.get("status").asString())) {
                throw ApiException.conflict("job_running", job + " for " + businessDate + " is already running");
            }
            return response(existing, true);
        }
        UUID runId = UUID.fromString(claimed.get().get("id").asString());
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            body.run(counts, errors);
        } catch (RuntimeException e) {
            log.error("scheduled job {} for {} failed", job, businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = store.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        JsonNode done = store.updateReturning("""
                update report_job_run t set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(t)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId);
        return response(done, false);
    }

    private Optional<JsonNode> claim(String job, LocalDate date) {
        String principal = TenantContext.principal();
        return store.row("""
                insert into report_job_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
                                                 created_by, updated_by)
                values (?, ?, ?, ?, 'RUNNING', 1, now(), ?, ?)
                on conflict (tenant_id, job, business_date) do update
                  set status = 'RUNNING', attempts = r.attempts + 1, started_at = now(), completed_at = null,
                      result = null, updated_by = excluded.updated_by
                  where r.status = 'COMPLETED_WITH_ERRORS'
                     or (r.status = 'RUNNING' and r.started_at < now() - make_interval(hours => ?))
                returning row_to_json(r)::text""",
                Ids.newId(), TenantContext.require(), job, date, principal, principal, STALE_HOURS);
    }

    private ObjectNode response(JsonNode run, boolean replayed) {
        ObjectNode out = store.object();
        out.put("job_run_id", run.get("id").asString());
        out.put("job", run.get("job").asString());
        out.put("business_date", run.get("business_date").asString());
        out.put("status", run.get("status").asString());
        out.put("attempts", run.path("attempts").asInt(1));
        out.put("replayed", replayed);
        out.put("started_at", Fields.text(run, "started_at"));
        out.put("completed_at", Fields.text(run, "completed_at"));
        if (run.path("result").isObject()) {
            out.setAll((ObjectNode) run.get("result"));
        }
        return out;
    }

    public static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }
}
