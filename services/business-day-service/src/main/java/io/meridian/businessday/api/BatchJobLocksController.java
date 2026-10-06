package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.BatchJobLocksApi;
import io.meridian.businessday.api.generated.model.BatchJobLock;
import io.meridian.businessday.api.generated.model.BatchJobLocksForceReleaseRequest;
import io.meridian.businessday.application.JobScheduleService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Batch job locks (leases); operators can force-release a stale lock, with a recorded reason. */
@RestController
public class BatchJobLocksController implements BatchJobLocksApi {
    private final JobScheduleService schedules;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public BatchJobLocksController(JobScheduleService schedules, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.schedules = schedules;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<BatchJobLock> batchJobLocksForceRelease(UUID id, String idempotencyKey,
                                                                  BatchJobLocksForceReleaseRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, BatchJobLock.class,
                () -> ResponseEntity.ok(crud.read(schedules.forceRelease(id, reason), BatchJobLock.class)));
    }
}
