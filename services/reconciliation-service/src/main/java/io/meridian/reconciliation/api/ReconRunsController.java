package io.meridian.reconciliation.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.api.generated.ReconRunsApi;
import io.meridian.reconciliation.api.generated.model.ReconRun;
import io.meridian.reconciliation.api.generated.model.ReconRunCreate;
import io.meridian.reconciliation.application.RunService;
import io.meridian.web.idempotency.Idempotency;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Creating a run executes the matching synchronously; the response carries the final status and counts. */
@RestController
public class ReconRunsController implements ReconRunsApi {
    private final RunService runs;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReconRunsController(RunService runs, Idempotency idempotency, Responses responses, JsonMapper json) {
        this.runs = runs;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReconRun> reconRunsCreate(String idempotencyKey, ReconRunCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, ReconRun.class,
                () -> responses.created(runs.create(req), ReconRun.class));
    }
}
