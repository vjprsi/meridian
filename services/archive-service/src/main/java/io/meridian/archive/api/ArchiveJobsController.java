package io.meridian.archive.api;

import io.meridian.archive.api.generated.ArchiveJobsApi;
import io.meridian.archive.api.generated.model.ArchiveJob;
import io.meridian.archive.api.generated.model.ArchiveJobCreate;
import io.meridian.archive.api.generated.model.ArchivedObjectPage;
import io.meridian.archive.application.ArchiveJobService;
import io.meridian.archive.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Archive runs: validated creation (queued, executed asynchronously), cancellation, produced objects. */
@RestController
public class ArchiveJobsController implements ArchiveJobsApi {
    private final ArchiveJobService jobs;
    private final Rows rows;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ArchiveJobsController(ArchiveJobService jobs, Rows rows, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.jobs = jobs;
        this.rows = rows;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ArchiveJob> archiveJobsCreate(String idempotencyKey, ArchiveJobCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ArchiveJob.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(crud.read(jobs.create(crud.toColumns("archive_job", body)), ArchiveJob.class)));
    }

    @Override
    public ResponseEntity<ArchiveJob> archiveJobsCancel(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, json.createObjectNode().put("cancel", id.toString()), ArchiveJob.class,
                () -> ResponseEntity.ok(crud.read(jobs.cancel(id), ArchiveJob.class)));
    }

    @Override
    public ResponseEntity<ArchivedObjectPage> archiveJobsObjects(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.requireText("archive_job", id, false);
        return crud.list("archived_object", ArchivedObjectPage.class,
                Crud.params().with("archive_job_id", id).with("limit", limit).with("cursor", cursor));
    }
}
