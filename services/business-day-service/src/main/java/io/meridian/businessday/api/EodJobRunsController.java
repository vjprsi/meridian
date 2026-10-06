package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.EodJobRunsApi;
import io.meridian.businessday.api.generated.model.EodJobRun;
import io.meridian.businessday.api.generated.model.EodJobRunsSkipRequest;
import io.meridian.businessday.application.EodRunService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Job executions inside an EOD run: retry a failed job, skip a failed non-critical job (maker-checker). */
@RestController
public class EodJobRunsController implements EodJobRunsApi {
    private final EodRunService eod;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public EodJobRunsController(EodRunService eod, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.eod = eod;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<EodJobRun> eodJobRunsRetry(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, json.createObjectNode().put("retry", id.toString()), EodJobRun.class,
                () -> ResponseEntity.ok(crud.read(eod.retry(id), EodJobRun.class)));
    }

    @Override
    public ResponseEntity<EodJobRun> eodJobRunsSkip(UUID id, String idempotencyKey, EodJobRunsSkipRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, EodJobRun.class,
                () -> ResponseEntity.ok(crud.read(eod.skip(id, reason), EodJobRun.class)));
    }
}
