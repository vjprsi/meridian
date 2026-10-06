package io.meridian.search.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.search.api.generated.SearchIndexJobsApi;
import io.meridian.search.api.generated.model.SearchIndexJob;
import io.meridian.search.application.IndexJobs;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Reindex / catch-up jobs: cancellation (a cancelled reindex leaves the live alias untouched). */
@RestController
public class SearchIndexJobsController implements SearchIndexJobsApi {
    private final IndexJobs jobs;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public SearchIndexJobsController(IndexJobs jobs, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.jobs = jobs;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<SearchIndexJob> searchIndexJobsCancel(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, json.createObjectNode().put("cancel", id.toString()), SearchIndexJob.class,
                () -> ResponseEntity.ok(crud.read(jobs.cancel(id), SearchIndexJob.class)));
    }
}
