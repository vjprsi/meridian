package io.meridian.regulatoryreporting.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.ReportSchedulesApi;
import io.meridian.regulatoryreporting.api.generated.model.ReportSchedule;
import io.meridian.regulatoryreporting.api.generated.model.ReportScheduleCreate;
import io.meridian.regulatoryreporting.api.generated.model.ReportScheduleUpdate;
import io.meridian.regulatoryreporting.application.Fields;
import io.meridian.regulatoryreporting.application.ScheduleService;
import io.meridian.regulatoryreporting.application.ScheduledJobs;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Report schedules (validated cron / time zone, computed next run) and the per-tenant run-due job. */
@RestController
public class ReportSchedulesController implements ReportSchedulesApi {
    private final ScheduleService schedules;
    private final ScheduledJobs jobs;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public ReportSchedulesController(ScheduleService schedules, ScheduledJobs jobs, Idempotency idempotency,
                                     Responses responses, CrudEngine crud, Store store, JsonMapper json) {
        this.schedules = schedules;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReportSchedule> reportSchedulesCreate(String idempotencyKey, ReportScheduleCreate body) {
        TenantContext.require();
        ObjectNode values = crud.toColumns("report_schedule", body);
        schedules.prepare(values, null);
        return idempotency.execute(idempotencyKey, body, ReportSchedule.class,
                () -> responses.created(crud.insert("report_schedule", values), ReportSchedule.class));
    }

    @Override
    public ResponseEntity<ReportSchedule> reportSchedulesUpdate(UUID id, String ifMatch, ReportScheduleUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = store.require("report_schedule", id);
        ObjectNode values = crud.toColumns("report_schedule", body);
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), ReportSchedule.class);
        }
        schedules.prepare(values, existing);
        String row = crud.updateRow("report_schedule", Map.of("id", id), values, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, ReportSchedule.class);
    }

    @Override
    public ResponseEntity<Void> reportSchedulesDelete(UUID id) {
        TenantContext.require();
        store.require("report_schedule", id);
        if (store.count("select count(*) from report_run where report_schedule_id = ?", id) > 0) {
            throw ApiException.conflict("schedule_has_runs", "The schedule produced runs; deactivate it (is_active=false) instead");
        }
        return crud.delete("report_schedule", Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> reportSchedulesRunDue(LocalDate businessDate, String idempotencyKey) {
        TenantContext.require();
        JsonNode fingerprint = json.createObjectNode().put("job", "SCHEDULED_RUNS").put("business_date", String.valueOf(businessDate));
        return responses.object(idempotency.execute(idempotencyKey, fingerprint, JsonNode.class,
                () -> ResponseEntity.ok(jobs.run("SCHEDULED_RUNS", businessDate,
                        (counts, errors) -> schedules.runDue(businessDate, counts, errors)))));
    }
}
