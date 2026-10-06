package io.meridian.businessday.application;

import io.meridian.businessday.domain.BusinessCalendar;
import io.meridian.businessday.domain.BusinessDayState;
import io.meridian.businessday.domain.CycleType;
import io.meridian.businessday.domain.DomainException;
import io.meridian.businessday.domain.JobGraph;
import io.meridian.businessday.infrastructure.LockStore;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** EOD cycle commands: start, restart from failure, cancel, retry a failed job, skip a failed job (four-eyes). */
@Service
public class EodRunService {
    private final Rows rows;
    private final CrudEngine crud;
    private final JobDefinitions definitions;
    private final LockStore locks;
    private final OutboxWriter outbox;
    private final EodOrchestrator orchestrator;
    private final EodSettings settings;
    private final JsonMapper json;

    public EodRunService(Rows rows, CrudEngine crud, JobDefinitions definitions, LockStore locks, OutboxWriter outbox,
                         EodOrchestrator orchestrator, EodSettings settings, JsonMapper json) {
        this.rows = rows;
        this.crud = crud;
        this.definitions = definitions;
        this.locks = locks;
        this.outbox = outbox;
        this.orchestrator = orchestrator;
        this.settings = settings;
        this.json = json;
    }

    /** Starts the cycle (implicit cut-off) and dispatches it asynchronously; returns the RUNNING run. */
    @Transactional
    public String start(UUID businessDayId, String cycleTypeText) {
        JsonNode day = rows.require("business_day", businessDayId, true);
        BusinessDayState state = BusinessDayState.of(day.get("state").asString());
        state.requireStartEod();
        if (!day.path("is_current").asBoolean(false)) {
            throw ApiException.conflict("not_current_business_day", "EOD can only run for the current business date");
        }
        CycleType cycle = cycleTypeText != null ? CycleType.parse(cycleTypeText)
                : new BusinessCalendar.PeriodEnd(day.path("is_month_end").asBoolean(), day.path("is_quarter_end").asBoolean(),
                        day.path("is_year_end").asBoolean()).defaultCycle();
        UUID entity = UUID.fromString(day.get("legal_entity_id").asString());
        JobGraph.of(definitions.all().stream().map(d -> new JobGraph.Job(d.code(), d.dependsOn(), d.sequence(),
                d.critical())).toList()).requireValid();
        List<JobDefinitions.Definition> plan = definitions.planFor(cycle, entity);
        definitions.graphOf(plan).levels();

        UUID runId = Ids.newId();
        acquire(day, runId, entity);
        ObjectNode run = runValues(runId, day, cycle.name());
        run.put("jobs_total", plan.size());
        crud.insert("eod_run", run);
        for (JobDefinitions.Definition d : plan) {
            for (int p = 0; p < d.parallelism(); p++) {
                insertJobRun(runId, d.id(), p, "QUEUED", null);
            }
        }
        if (state == BusinessDayState.OPEN) {
            String cut = rows.jdbc().sql("""
                    update business_day set state = 'CUT_OFF', cut_off_at = now() where id = ?
                    returning row_to_json(business_day)::text""").param(businessDayId).query(String.class).single();
            outbox.publish("operations.business_day.cut_off", "business_day", businessDayId, json.readTree(cut));
        }
        rows.update("update business_day set state = 'EOD_RUNNING', updated_by = ? where id = ?",
                TenantContext.principal(), businessDayId);
        String row = rows.requireText("eod_run", runId, false);
        outbox.publish("operations.eod_run.started", "eod_run", runId, json.readTree(row));
        orchestrator.launchAfterCommit(runId);
        return row;
    }

    /** New run for the same date that skips jobs already SUCCEEDED (or skipped) and resumes failed ones from their checkpoint. */
    @Transactional
    public String restart(UUID runId) {
        JsonNode prev = rows.require("eod_run", runId, true);
        String status = prev.get("status").asString();
        if (!List.of("FAILED", "CANCELLED", "PARTIALLY_SUCCEEDED").contains(status)) {
            throw DomainException.invalidTransition("EOD run", status, "be restarted");
        }
        UUID dayId = UUID.fromString(prev.get("business_day_id").asString());
        requireLatest(prev, dayId);
        JsonNode day = rows.require("business_day", dayId, true);
        BusinessDayState.of(day.get("state").asString()).requireResumeEod();
        UUID entity = UUID.fromString(day.get("legal_entity_id").asString());
        UUID newRunId = Ids.newId();
        acquire(day, newRunId, entity);
        List<JsonNode> prevJobs = rows.query(
                "select row_to_json(j)::text from eod_job_run j where eod_run_id = ? order by partition_no, id", runId);
        Map<String, List<JsonNode>> byDef = new LinkedHashMap<>();
        prevJobs.forEach(j -> byDef.computeIfAbsent(j.get("eod_job_definition_id").asString(), k -> new java.util.ArrayList<>()).add(j));
        ObjectNode run = runValues(newRunId, day, prev.get("cycle_type").asString());
        run.put("is_restart", true);
        run.put("restart_of_id", runId.toString());
        run.put("jobs_total", byDef.size());
        crud.insert("eod_run", run);
        byDef.forEach((defId, jobs) -> {
            boolean done = jobs.stream().allMatch(j -> "SUCCEEDED".equals(j.get("status").asString()) || j.hasNonNull("skipped_by"));
            for (JsonNode j : jobs) {
                if (done) {
                    ObjectNode copy = json.createObjectNode();
                    for (String f : List.of("partition_no", "attempt", "status", "started_at", "finished_at",
                            "duration_ms", "records_processed", "records_failed", "skipped_by", "skip_reason", "error_code")) {
                        if (j.hasNonNull(f)) {
                            copy.set(f, j.get(f));
                        }
                    }
                    copy.put("eod_run_id", newRunId.toString());
                    copy.put("eod_job_definition_id", defId);
                    copy.putObject("checkpoint").put("carried_over_from", j.get("id").asString());
                    crud.insert("eod_job_run", copy);
                } else {
                    insertJobRun(newRunId, UUID.fromString(defId), j.path("partition_no").asInt(), "QUEUED",
                            j.hasNonNull("checkpoint") ? j.get("checkpoint") : null);
                }
            }
        });
        rows.update("update business_day set state = 'EOD_RUNNING', updated_by = ? where id = ?",
                TenantContext.principal(), dayId);
        String row = rows.requireText("eod_run", newRunId, false);
        outbox.publish("operations.eod_run.started", "eod_run", newRunId, json.readTree(row));
        orchestrator.launchAfterCommit(newRunId);
        return row;
    }

    @Transactional
    public String cancel(UUID runId, String reason) {
        JsonNode run = rows.require("eod_run", runId, true);
        String status = run.get("status").asString();
        if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
            throw DomainException.invalidTransition("EOD run", status, "be cancelled");
        }
        String row = rows.jdbc().sql("""
                update eod_run set status = 'CANCELLED', finished_at = now(), error = ?, updated_by = ?
                where id = ? returning row_to_json(eod_run)::text""")
                .params("Cancelled by " + TenantContext.principal() + ": " + reason, TenantContext.principal(), runId)
                .query(String.class).single();
        rows.update("""
                update eod_job_run set status = 'CANCELLED', error_code = 'cancelled', error = ?, finished_at = now()
                where eod_run_id = ? and status = 'QUEUED'""", "Run cancelled: " + reason, runId);
        UUID dayId = UUID.fromString(run.get("business_day_id").asString());
        rows.update("update business_day set state = 'EOD_FAILED' where id = ? and state = 'EOD_RUNNING'", dayId);
        outbox.publish("operations.eod_run.failed", "eod_run", runId, json.readTree(row));
        if (!orchestrator.isActive(runId)) {
            JsonNode day = rows.require("business_day", dayId, false);
            locks.release(EodOrchestrator.lockKey(run, day.get("business_date").asString()), settings.eodHolder(runId));
        }
        return row;
    }

    /** Re-dispatches a failed job (fresh retry budget) and resumes the run's remaining jobs. */
    @Transactional
    public String retry(UUID jobRunId) {
        JsonNode jr = rows.require("eod_job_run", jobRunId, true);
        if (!"FAILED".equals(jr.get("status").asString())) {
            throw DomainException.invalidTransition("Job execution", jr.get("status").asString(), "be retried");
        }
        JsonNode run = requireResumableRun(jr);
        rows.update("""
                update eod_job_run set status = 'QUEUED', attempt = attempt + 1, error_code = null, error = null,
                       started_at = null, finished_at = null, duration_ms = null, updated_by = ?
                where id = ?""", TenantContext.principal(), jobRunId);
        resume(run);
        return rows.requireText("eod_job_run", jobRunId, false);
    }

    /** Skips a failed non-critical job; the checker must differ from the user who started the cycle. */
    @Transactional
    public String skip(UUID jobRunId, String reason) {
        JsonNode jr = rows.require("eod_job_run", jobRunId, true);
        if (!"FAILED".equals(jr.get("status").asString())) {
            throw DomainException.invalidTransition("Job execution", jr.get("status").asString(), "be skipped");
        }
        JsonNode def = rows.require("eod_job_definition", UUID.fromString(jr.get("eod_job_definition_id").asString()), false);
        if (def.path("is_critical").asBoolean(true)) {
            throw ApiException.unprocessable("critical_job_not_skippable",
                    "Job " + def.get("code").asString() + " is critical and cannot be skipped; retry or fix it");
        }
        JsonNode run = requireResumableRun(jr);
        if (Objects.equals(run.path("triggered_by").asString(null), TenantContext.principal())) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "Skipping a job must be approved by a different user than the one who started EOD");
        }
        rows.update("""
                update eod_job_run set status = 'CANCELLED', skipped_by = ?, skip_reason = ?, error_code = 'skipped',
                       finished_at = coalesce(finished_at, now()), updated_by = ?
                where id = ?""", TenantContext.principal(), reason, TenantContext.principal(), jobRunId);
        resume(run);
        return rows.requireText("eod_job_run", jobRunId, false);
    }

    private JsonNode requireResumableRun(JsonNode jr) {
        UUID runId = UUID.fromString(jr.get("eod_run_id").asString());
        JsonNode run = rows.require("eod_run", runId, true);
        String status = run.get("status").asString();
        if (!"FAILED".equals(status) && !"PARTIALLY_SUCCEEDED".equals(status)) {
            throw ApiException.conflict("run_not_resumable", "EOD run is " + status
                    + "; jobs can be retried or skipped only on a FAILED or PARTIALLY_SUCCEEDED run (restart a cancelled one)");
        }
        UUID dayId = UUID.fromString(run.get("business_day_id").asString());
        requireLatest(run, dayId);
        return run;
    }

    private void resume(JsonNode run) {
        UUID runId = UUID.fromString(run.get("id").asString());
        UUID dayId = UUID.fromString(run.get("business_day_id").asString());
        JsonNode day = rows.require("business_day", dayId, true);
        BusinessDayState.of(day.get("state").asString()).requireResumeEod();
        acquire(day, runId, UUID.fromString(day.get("legal_entity_id").asString()));
        orchestrator.resetBlocked(runId);
        rows.update("""
                update eod_run set status = 'RUNNING', finished_at = null, error = null, updated_by = ? where id = ?""",
                TenantContext.principal(), runId);
        rows.update("update business_day set state = 'EOD_RUNNING' where id = ?", dayId);
        orchestrator.launchAfterCommit(runId);
    }

    private void requireLatest(JsonNode run, UUID dayId) {
        UUID latest = rows.jdbc().sql("select id from eod_run where business_day_id = ? order by created_at desc, id desc limit 1")
                .param(dayId).query(UUID.class).single();
        if (!latest.toString().equals(run.get("id").asString())) {
            throw ApiException.conflict("not_latest_run", "A newer EOD run " + latest + " exists for this business date");
        }
    }

    private void acquire(JsonNode day, UUID runId, UUID entity) {
        String key = "eod:" + entity + ":" + day.get("business_date").asString();
        if (locks.acquire(key, settings.eodHolder(runId), settings.lockLease()).isEmpty()) {
            throw ApiException.conflict("eod_locked", "Another EOD execution holds the lock " + key);
        }
    }

    private ObjectNode runValues(UUID runId, JsonNode day, String cycle) {
        ObjectNode run = json.createObjectNode();
        run.put("id", runId.toString());
        run.put("business_day_id", day.get("id").asString());
        run.put("legal_entity_id", day.get("legal_entity_id").asString());
        run.put("cycle_type", cycle);
        run.put("status", "RUNNING");
        run.put("triggered_by", TenantContext.principal());
        run.put("workflow_id", "eod-" + day.get("legal_entity_id").asString() + "-" + day.get("business_date").asString()
                + "-" + runId);
        run.put("started_at", java.time.OffsetDateTime.now().toString());
        run.put("jobs_succeeded", 0);
        run.put("jobs_failed", 0);
        return run;
    }

    private void insertJobRun(UUID runId, UUID defId, int partition, String status, JsonNode checkpoint) {
        ObjectNode jr = json.createObjectNode();
        jr.put("eod_run_id", runId.toString());
        jr.put("eod_job_definition_id", defId.toString());
        jr.put("partition_no", partition);
        jr.put("attempt", 1);
        jr.put("status", status);
        if (checkpoint != null) {
            jr.set("checkpoint", checkpoint);
        }
        crud.insert("eod_job_run", jr);
    }
}
