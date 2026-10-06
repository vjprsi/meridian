package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.EodRunsApi;
import io.meridian.businessday.api.generated.model.EodJobRunPage;
import io.meridian.businessday.api.generated.model.EodRun;
import io.meridian.businessday.api.generated.model.EodRunsCancelRequest;
import io.meridian.businessday.application.EodRunService;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** EOD runs: job executions, restart from failure, cancel. */
@RestController
public class EodRunsController implements EodRunsApi {
    private final EodRunService eod;
    private final CrudEngine crud;
    private final Rows rows;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public EodRunsController(EodRunService eod, CrudEngine crud, Rows rows, Idempotency idempotency, JsonMapper json) {
        this.eod = eod;
        this.crud = crud;
        this.rows = rows;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<EodJobRunPage> eodRunsJobs(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.requireText("eod_run", id, false);
        return crud.list("eod_job_run", EodJobRunPage.class,
                Crud.params().with("eod_run_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<EodRun> eodRunsRestart(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, json.createObjectNode().put("restart", id.toString()), EodRun.class,
                () -> ResponseEntity.ok(crud.read(eod.restart(id), EodRun.class)));
    }

    @Override
    public ResponseEntity<EodRun> eodRunsCancel(UUID id, String idempotencyKey, EodRunsCancelRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, EodRun.class,
                () -> ResponseEntity.ok(crud.read(eod.cancel(id, reason), EodRun.class)));
    }
}
