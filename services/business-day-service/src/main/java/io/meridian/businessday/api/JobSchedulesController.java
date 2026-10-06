package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.JobSchedulesApi;
import io.meridian.businessday.api.generated.model.JobSchedule;
import io.meridian.businessday.api.generated.model.JobScheduleCreate;
import io.meridian.businessday.api.generated.model.JobScheduleRun;
import io.meridian.businessday.api.generated.model.JobScheduleRunPage;
import io.meridian.businessday.api.generated.model.JobScheduleUpdate;
import io.meridian.businessday.api.generated.model.JobSchedulesRunNowRequest;
import io.meridian.businessday.application.JobScheduleService;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Cron job schedules: validated create/update with next-run computation, pause, resume, run now, history. */
@RestController
public class JobSchedulesController implements JobSchedulesApi {
    private static final String TABLE = "job_schedule";

    private final JobScheduleService schedules;
    private final CrudEngine crud;
    private final Rows rows;
    private final Idempotency idempotency;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public JobSchedulesController(JobScheduleService schedules, CrudEngine crud, Rows rows, Idempotency idempotency,
                                  TransactionTemplate tx, JsonMapper json) {
        this.schedules = schedules;
        this.crud = crud;
        this.rows = rows;
        this.idempotency = idempotency;
        this.tx = tx;
        this.json = json;
    }

    @Override
    public ResponseEntity<JobSchedule> jobSchedulesCreate(String idempotencyKey, JobScheduleCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, JobSchedule.class, () -> {
            ObjectNode values = schedules.prepare(crud.toColumns(TABLE, body));
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), JobSchedule.class));
        });
    }

    @Override
    public ResponseEntity<JobSchedule> jobSchedulesUpdate(UUID id, String ifMatch, JobScheduleUpdate body) {
        TenantContext.require();
        int version = Http.ifMatch(ifMatch);
        return tx.execute(s -> {
            JsonNode current = rows.require(TABLE, id, true);
            ObjectNode changes = crud.toColumns(TABLE, body);
            if (changes.hasNonNull("status") && !changes.get("status").asString().equals(current.get("status").asString())
                    && !"DISABLED".equals(changes.get("status").asString())) {
                throw ApiException.unprocessable("use_action_endpoint",
                        "Use /pause and /resume to change the status (PATCH can only disable a schedule)");
            }
            ObjectNode merged = ((ObjectNode) current).deepCopy();
            merged.setAll(changes);
            schedules.prepare(merged);
            changes.set("next_run_at", merged.get("next_run_at"));
            String row = crud.updateRow(TABLE, Map.of("id", id), changes, version)
                    .orElseThrow(() -> ApiException.conflict("version_conflict",
                            "If-Match " + version + " does not match the current version; reload and retry"));
            return ResponseEntity.ok().eTag("\"" + json.readTree(row).get("row_version").asString() + "\"")
                    .body(crud.read(row, JobSchedule.class));
        });
    }

    @Override
    public ResponseEntity<Void> jobSchedulesDelete(UUID id) {
        TenantContext.require();
        return tx.execute(s -> {
            rows.require(TABLE, id, true);
            long runs = rows.jdbc().sql("select count(*) from job_schedule_run where job_schedule_id = ?")
                    .param(id).query(Long.class).single();
            if (runs > 0) {
                throw ApiException.conflict("schedule_has_runs",
                        "The schedule has an execution history; disable it instead of deleting");
            }
            rows.update("delete from job_schedule where id = ?", id);
            return ResponseEntity.noContent().build();
        });
    }

    @Override
    public ResponseEntity<JobSchedule> jobSchedulesPause(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, json.createObjectNode().put("pause", id.toString()), JobSchedule.class,
                () -> ResponseEntity.ok(crud.read(schedules.pause(id), JobSchedule.class)));
    }

    @Override
    public ResponseEntity<JobSchedule> jobSchedulesResume(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, json.createObjectNode().put("resume", id.toString()), JobSchedule.class,
                () -> ResponseEntity.ok(crud.read(schedules.resume(id), JobSchedule.class)));
    }

    @Override
    public ResponseEntity<JobScheduleRun> jobSchedulesRunNow(UUID id, String idempotencyKey, JobSchedulesRunNowRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, JobScheduleRun.class,
                () -> ResponseEntity.ok(crud.read(schedules.runNow(id, req.get("parameters"), idempotencyKey),
                        JobScheduleRun.class)));
    }

    @Override
    public ResponseEntity<JobScheduleRunPage> jobSchedulesRuns(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.requireText(TABLE, id, false);
        return crud.list("job_schedule_run", JobScheduleRunPage.class,
                Crud.params().with("job_schedule_id", id).with("limit", limit).with("cursor", cursor));
    }
}
